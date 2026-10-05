/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.shared.KeyboardMode
import io.github.kiriashi.biopay.biometric.BiometricAuth
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.storage.PasswordCipher
import java.lang.ref.WeakReference

class PaymentFlow(private val state: AppRuntime) {
    private val attachLock = Any()
    private var attachListener: KeyboardAttachListener? = null
    private var attachedViewRef: WeakReference<ViewGroup>? = null
    private val setupLock = Any()
    private val tasks = MainTasks()
    @Volatile private var pendingMarkUsed: (() -> Unit)? = null

    fun setupBiometricAuth(
        keyboardView: ViewGroup, encodedPassword: String,
        hostActivity: Activity? = null, startImmediately: Boolean = true,
        keyboardMode: KeyboardMode = KeyboardMode.APP,
        onContinue: (() -> Unit)? = null
    ): Boolean {
        if (state.isClosed) return false
        val config = state.prefs.activeConfig()?.takeIf { it.encryptedPassword == encodedPassword } ?: return false
        val (sessionId, shouldTrigger) = synchronized(setupLock) {
            val alreadyInProgress = state.session.isAuthenticationInProgress() ||
                PasswordAutoInput.isInProgress(state.session.currentSessionId())
            val id = if (alreadyInProgress) state.session.currentSessionId() else state.session.beginSession()
            removeListenersFromOldView()

            state.session.bindKeyboard(keyboardView, hostActivity ?: keyboardView.context.findActivity(), config, keyboardMode)

            // Visual payment screens are watched by VisualPaymentMonitor. Reattaching
            // their keyboard must not start a second automatic prompt after cancel.
            if (startImmediately && state.adapter.app == PaymentApp.WECHAT) {
                synchronized(attachLock) {
                    val listener = attachListener ?: KeyboardAttachListener().also { attachListener = it }
                    listener.keyboardView = keyboardView
                    listener.sessionId = id
                }
            }

            id to !alreadyInProgress
        }

        if (!startImmediately) return true
        if (state.adapter.app == PaymentApp.WECHAT) {
            val listener = synchronized(attachLock) { attachListener } ?: return false
            keyboardView.addOnAttachStateChangeListener(listener)
            synchronized(attachLock) { attachedViewRef = WeakReference(keyboardView) }
        }
        if (!shouldTrigger) return true
        return awaitContinue(keyboardView, sessionId, onContinue)
    }

    /** Covers the keypad and offers the two exits instead of opening the sheet directly. */
    private fun awaitContinue(
        keyboardView: ViewGroup, sessionId: Long, onContinue: (() -> Unit)?
    ): Boolean {
        pendingMarkUsed = onContinue
        if (!state.session.continueState.request()) return true
        // The Keystore key load is the only cost between the tap and the sheet; pay it up front.
        state.paymentWorker.submit(work = {
            if (state.isClosed) null else runCatching { PasswordCipher.warmUp() }.getOrNull()
        }, discard = { }) { }
        return drawCover(keyboardView, sessionId)
    }

    /**
     * Redraws the affordance when a layout pass finds the keypad alive but nothing on screen,
     * which happens after a payment Activity hides and shows its password sheet again.
     */
    fun refreshContinuePrompt(keyboardView: ViewGroup) {
        val sessionId = state.session.currentSessionId()
        if (state.isClosed || sessionId == 0L || state.session.continueState.manual) return
        if (!state.session.isCurrentSession(sessionId)) return
        if (state.session.isAuthenticationInProgress() || PasswordAutoInput.isInProgress(sessionId)) return
        drawCover(keyboardView, sessionId)
    }

    private fun drawCover(keyboardView: ViewGroup, sessionId: Long): Boolean = ContinuePrompt.show(
        state, keyboardView, sessionId,
        onContinue = ::openAuthentication,
        onManualEntry = { enterManualMode(sessionId) }
    )

    /** Opens the sheet against the keypad and password the session currently holds. */
    private fun openAuthentication() {
        if (state.isClosed) return
        val keyboardView = state.session.getCurrentKeyboardView() ?: return
        val encodedPassword = state.session.getCurrentEncodedPassword()
            ?: state.prefs.activePassword() ?: return
        val sessionId = state.session.currentSessionId()
        if (state.session.isAuthenticationInProgress() ||
            PasswordAutoInput.isInProgress(sessionId)
        ) return
        // Consumed here so a bail below can leave the screen asking again.
        val markUsed = pendingMarkUsed
        pendingMarkUsed = null
        if (BiometricAuth.triggerBiometricAuth(keyboardView, encodedPassword, state, sessionId)) {
            markUsed?.invoke()
        } else {
            // The sheet never launched; put the exits back rather than leaving a dead tap.
            drawCover(keyboardView, sessionId)
        }
    }

    /** The user took over typing; the keypad and its input method go back to them. */
    private fun enterManualMode(sessionId: Long) {
        if (state.isClosed) return
        if (state.session.isCurrentSession(sessionId)) {
            state.session.continueState.enterManual()
            ContinuePrompt.dismiss()
        }
        state.session.restoreKeyboard(sessionId)
    }

    /**
     * The sheet closed without entering the password. Cover the keypad again: both exits only
     * exist on the cover, so leaving it down would strand the user with a key gesture.
     */
    fun authenticationAbandoned(sessionId: Long) {
        if (state.isClosed || !state.session.isCurrentSession(sessionId)) return
        if (state.session.isAuthenticationInProgress()) return
        val keyboardView = state.session.getCurrentKeyboardView() ?: return
        if (!state.session.continueState.request()) return
        drawCover(keyboardView, sessionId)
    }

    fun toggleBetweenBiometricAndKeyboard() {
        if (state.isClosed) return
        try {
            if (state.session.isAuthenticationInProgress()) {
                state.session.cancelAuthentication()
            } else if (!PasswordAutoInput.isInProgress(state.session.currentSessionId())) {
                if (ContinuePrompt.triggerNow()) return
                state.prefs.activeConfig()?.let { state.session.bindConfig(it) }
                openAuthentication()
            }
        } catch (e: Throwable) {
            ModuleLog.d(e) { "toggleBetweenBiometricAndKeyboard failed" }
        }
    }

    fun reset() {
        PasswordAutoInput.cancelPendingRunnables()
        InputMask.reset()
        ContinuePrompt.dismiss()
        pendingMarkUsed = null
        synchronized(attachLock) {
            attachListener?.let { l ->
                val view = attachedViewRef?.get()
                tasks.onMain { view?.removeOnAttachStateChangeListener(l) }
            }
            attachListener = null
            attachedViewRef = null
        }
    }

    private fun removeListenersFromOldView() {
        synchronized(attachLock) {
            val oldView = attachedViewRef?.get()
            if (oldView != null) {
                attachListener?.let { oldView.removeOnAttachStateChangeListener(it) }
            }
            attachedViewRef = null
        }
    }

    private inner class KeyboardAttachListener : View.OnAttachStateChangeListener {
        private var keyboardViewRef: WeakReference<ViewGroup>? = null
        var keyboardView: ViewGroup?
            get() = keyboardViewRef?.get()
            set(value) { keyboardViewRef = value?.let(::WeakReference) }
        var sessionId: Long = 0L

        override fun onViewAttachedToWindow(view: View) {
            if (state.isClosed || keyboardView !== view || state.session.getCurrentKeyboardView() !== view) return
            keyboardView?.let { kv ->
                if (state.session.isCurrentSession(sessionId) &&
                    !state.session.isAuthenticationInProgress() &&
                    !PasswordAutoInput.isInProgress(sessionId)) {
                    val encoded = state.prefs.activePassword()
                    if (!encoded.isNullOrEmpty()) {
                        ModuleLog.d { "onViewAttached: requesting confirmation, view=${kv.hashCode()}" }
                        awaitContinue(kv, sessionId, null)
                    }
                } else {
                    ModuleLog.d { "onViewAttached: biometric in progress, skipping, view=${kv.hashCode()}" }
                }
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            val detachedView = view as? ViewGroup ?: return
            view.removeOnAttachStateChangeListener(this)
            if (state.session.getCurrentKeyboardView() !== detachedView) return

            InputMask.reset()
            PasswordAutoInput.cancelPendingRunnables()

            if (state.session.isAuthenticationInProgress()) {
                // Payment Activities can recreate the keyboard during authentication.
                ModuleLog.d { "onViewDetached: keeping payment session, view=${detachedView.hashCode()}" }
                return
            }

            ModuleLog.d { "onViewDetached: clearing payment state, view=${detachedView.hashCode()}" }
            state.session.endSession(sessionId)
            keyboardView = null
            sessionId = 0L
        }
    }
}
