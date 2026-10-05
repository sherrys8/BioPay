/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.kiriashi.biopay.payment

import android.app.Activity
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.github.kiriashi.biopay.apps.shared.KeyboardMode
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.storage.PaymentConfig
import java.lang.ref.WeakReference

class PaymentSession(private val onDestroy: () -> Unit = {}) {
    private val sessionToken = SessionToken()
    private val authenticationToken = SessionToken()
    private val tasks = MainTasks()
    private val ime = PaymentIme()
    private val signalLock = Any()
    internal val continueState = ContinueState()

    data class AuthenticationAttempt(val id: Long, val signal: CancellationSignal)

    @Volatile private var cancelSignal: CancellationSignal? = null
    @Volatile private var config: PaymentConfig? = null
    @Volatile private var currentKeyboardViewRef: WeakReference<ViewGroup>? = null
    @Volatile private var inputEditTextRef: WeakReference<EditText>? = null
    @Volatile private var confirmButtonRef: WeakReference<View>? = null
    @Volatile private var hostActivityRef: WeakReference<Activity>? = null
    @Volatile private var keyboardMode = KeyboardMode.UNKNOWN
    @Volatile private var inputHandoff = false
    private var authenticationCleanup: (() -> Unit)? = null
    @Volatile private var manualImeRestore: Runnable? = null

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (currentSessionId() == 0L) return
            cleanupExpiredReferences()
            if (currentSessionId() != 0L) tasks.post(this, CLEANUP_INTERVAL_MS)
        }
    }

    fun isInPaymentMode(): Boolean = currentSessionId() != 0L && config != null

    fun beginSession(): Long {
        reset(clearBindings = false)
        val id = sessionToken.begin()
        tasks.post(cleanupRunnable, CLEANUP_INTERVAL_MS)
        return id
    }

    internal fun bindKeyboard(
        keyboard: ViewGroup, activity: Activity?, settings: PaymentConfig, keyboardMode: KeyboardMode = KeyboardMode.APP
    ) {
        currentKeyboardViewRef = WeakReference(keyboard)
        hostActivityRef = activity?.let(::WeakReference)
        config = settings
        applyKeyboardMode(keyboardMode)
        updateInputMethod()
    }

    internal fun bindConfig(settings: PaymentConfig) {
        config = settings
    }

    internal fun currentConfig(): PaymentConfig? = config

    fun isCurrentSession(id: Long): Boolean = sessionToken.isCurrent(id)
    fun currentSessionId(): Long = sessionToken.current()

    fun endSession(id: Long) {
        if (isCurrentSession(id)) destroy()
    }

    fun endSessionForActivity(activity: Activity) {
        if (getCurrentKeyboardView()?.context?.findActivity() === activity || getHostActivity() === activity) destroy()
    }

    fun getHostActivity(): Activity? = hostActivityRef?.get()
    fun getCurrentKeyboardView(): ViewGroup? = currentKeyboardViewRef?.get()
    fun getCurrentEncodedPassword(): String? = config?.encryptedPassword

    fun setInputEditText(editText: EditText?) {
        if (editText == null || inputEditTextRef?.get() !== editText) inputEditTextRef = editText?.let(::WeakReference)
        updateInputMethod()
    }

    fun suppressInputMethod() {
        if (!isAuthenticationInProgress()) return
        manualImeRestore?.let(tasks::cancel)
        manualImeRestore = null
        updateInputMethod(force = true)
    }

    internal fun updateKeyboardMode(mode: KeyboardMode) {
        if (applyKeyboardMode(mode)) updateInputMethod()
    }

    private fun applyKeyboardMode(mode: KeyboardMode): Boolean {
        // Partial layouts do not change an established mode; SYSTEM requires explicit page evidence.
        if (mode == KeyboardMode.UNKNOWN || keyboardMode == mode) return false
        ModuleLog.d { "payment keyboard mode: $keyboardMode -> $mode, session=${currentSessionId()}" }
        keyboardMode = mode
        return true
    }

    internal fun holdInputMethodForInput() {
        inputHandoff = true
        updateInputMethod()
    }

    private fun updateInputMethod(force: Boolean = false) {
        val authenticating = isAuthenticationInProgress()
        ime.update(getCurrentKeyboardView(), getInputEditText(),
            isInPaymentMode() && (authenticating || inputHandoff || keyboardMode != KeyboardMode.SYSTEM),
            authenticating, force)
    }

    fun getInputEditText(): EditText? = inputEditTextRef?.get()

    fun setConfirmButton(button: View?) {
        if (button == null || confirmButtonRef?.get() !== button) confirmButtonRef = button?.let(::WeakReference)
    }

    fun getConfirmButton(): View? = confirmButtonRef?.get()
    fun isCurrentAuthentication(id: Long): Boolean = authenticationToken.isCurrent(id)
    fun isAuthenticationInProgress(): Boolean = authenticationToken.current() != 0L

    fun beginAuthentication(): AuthenticationAttempt? = synchronized(signalLock) {
        if (!isInPaymentMode() || isAuthenticationInProgress()) return null
        val signal = CancellationSignal()
        cancelSignal = signal
        AuthenticationAttempt(authenticationToken.begin(), signal)
    }

    internal fun onAuthenticationEnded(id: Long, cleanup: () -> Unit): Boolean = synchronized(signalLock) {
        if (!authenticationToken.isCurrent(id) || authenticationCleanup != null) return false
        authenticationCleanup = cleanup
        true
    }

    fun finishAuthentication(id: Long): Boolean {
        val cleanup = synchronized(signalLock) {
            if (!authenticationToken.finish(id)) return false
            cancelSignal = null
            authenticationCleanup.also { authenticationCleanup = null }
        }
        runAuthenticationCleanup(cleanup)
        return true
    }

    fun cancelAuthentication() {
        cancelCurrentSignal()
        restoreKeyboard(currentSessionId())
    }

    private fun cancelCurrentSignal() {
        val (signal, cleanup) = synchronized(signalLock) {
            authenticationToken.invalidate()
            val signal = cancelSignal
            val cleanup = authenticationCleanup
            cancelSignal = null
            authenticationCleanup = null
            signal to cleanup
        }
        // Release attempt resources before framework cancellation can deliver callbacks.
        runAuthenticationCleanup(cleanup)
        try {
            signal?.cancel()
        } catch (error: Exception) {
            ModuleLog.w(error) { "payment authentication cancellation failed" }
        }
    }

    private fun runAuthenticationCleanup(cleanup: (() -> Unit)?) {
        try {
            cleanup?.invoke()
        } catch (error: Exception) {
            ModuleLog.w(error) { "payment authentication cleanup failed" }
        }
    }

    fun restoreKeyboard(id: Long) {
        tasks.onMain {
            if (!isCurrentSession(id) || isAuthenticationInProgress()) return@onMain
            manualImeRestore?.let(tasks::cancel)
            manualImeRestore = null
            InputMask.reset()
            inputHandoff = false
            updateInputMethod(force = keyboardMode != KeyboardMode.SYSTEM)
            val keyboard = getCurrentKeyboardView() ?: return@onMain
            keyboard.visibility = View.VISIBLE
            val input = getInputEditText() ?: return@onMain
            val activity = getHostActivity()
            if (!canRestoreInput(keyboard, input, activity)) return@onMain
            val needsInputMethod = keyboardMode == KeyboardMode.SYSTEM && input.showSoftInputOnFocus
            input.requestFocus()
            if (!needsInputMethod) return@onMain
            val root = keyboard.rootView
            val settings = currentConfig()
            val deadline = SystemClock.uptimeMillis() + MANUAL_IME_TIMEOUT_MS
            val restore = object : Runnable {
                override fun run() {
                    if (manualImeRestore !== this) return
                    if (!isCurrentSession(id) || isAuthenticationInProgress() || inputHandoff ||
                        getCurrentKeyboardView() !== keyboard || getInputEditText() !== input ||
                        keyboard.rootView !== root || currentConfig() != settings ||
                        keyboardMode != KeyboardMode.SYSTEM || !input.showSoftInputOnFocus ||
                        !canRestoreInput(keyboard, input, activity)) {
                        manualImeRestore = null
                        return
                    }
                    if (!input.hasFocus() || !input.hasWindowFocus()) {
                        if (SystemClock.uptimeMillis() < deadline) {
                            tasks.post(this, MANUAL_IME_RETRY_MS)
                        } else {
                            manualImeRestore = null
                            ModuleLog.d { "payment manual IME restoration skipped: window focus unavailable, session=$id" }
                        }
                        return
                    }
                    manualImeRestore = null
                    ModuleLog.d { "payment manual IME show requested, session=$id" }
                    ime.show(input)
                }
            }
            manualImeRestore = restore
            tasks.post(restore)
        }
    }

    private fun canRestoreInput(keyboard: ViewGroup, input: EditText, activity: Activity?): Boolean =
        keyboard.isAttachedToWindow && keyboard.isShown && keyboard.windowVisibility == View.VISIBLE &&
            input.isAttachedToWindow && input.isShown &&
            input.rootView === keyboard.rootView && getHostActivity() === activity &&
            activity?.isFinishing != true && activity?.isDestroyed != true

    private fun cleanupExpiredReferences() {
        val keyboard = getCurrentKeyboardView()
        val host = getHostActivity()
        if (!isAuthenticationInProgress() &&
            (keyboard == null || !keyboard.isAttachedToWindow || host?.isFinishing == true || host?.isDestroyed == true)
        ) {
            destroy()
        }
    }

    fun destroy() = reset(clearBindings = true)

    private fun reset(clearBindings: Boolean) {
        val hiddenKeyboard = if (isAuthenticationInProgress()) getCurrentKeyboardView() else null
        // Invalidate callbacks before canceling the prompt or restoring any UI.
        sessionToken.invalidate()
        cancelCurrentSignal()
        tasks.clear()
        manualImeRestore = null
        ime.clear()
        continueState.reset()
        keyboardMode = KeyboardMode.UNKNOWN
        inputHandoff = false
        if (clearBindings) {
            config = null
            currentKeyboardViewRef = null
            inputEditTextRef = null
            confirmButtonRef = null
            hostActivityRef = null
        }
        hiddenKeyboard?.let { keyboard -> tasks.onMain { keyboard.visibility = View.VISIBLE } }
        onDestroy()
    }

    private companion object {
        const val CLEANUP_INTERVAL_MS = 60_000L
        const val MANUAL_IME_TIMEOUT_MS = 1_000L
        const val MANUAL_IME_RETRY_MS = 50L
    }
}
