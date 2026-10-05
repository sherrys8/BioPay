/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.VisualPaymentAdapter

import android.app.Activity
import android.app.Dialog
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap

class VisualPaymentMonitor(private val state: AppRuntime, private val adapter: VisualPaymentAdapter) {
    private val observers = WeakHashMap<ViewGroup, LayoutObserver>()
    private val screenState = PaymentScreenState<ViewGroup>()
    @Volatile private var closed = false
    private val paymentExitCheck = object : Runnable {
        override fun run() {
            if (closed) return
            val sessionId = state.session.currentSessionId()
            if (state.session.isAuthenticationInProgress() ||
                PasswordAutoInput.isInProgress(sessionId)
            ) {
                tasks.post(this, PAYMENT_EXIT_GRACE_MS)
                return
            }
            if (!hasVisiblePaymentScreen()) clearPaymentScreen()
        }
    }
    private val tasks = MainTasks()
    private var paymentActivity: WeakReference<Activity>? = null
    private var lastPaymentActivity: WeakReference<Activity>? = null
    private var scanUntil = 0L
    private val windowScan = object : Runnable {
        override fun run() {
            if (closed) return
            val activity = paymentActivity?.get() ?: return
            if (activity.isFinishing || activity.isDestroyed || !adapter.supports(activity)) return
            if (!paymentEnabled()) {
                clearPaymentScreen()
                return
            }
            if (isBusy()) {
                screenState.screenAbsentTooLong(false, true, state.session.isInPaymentMode(), SystemClock.uptimeMillis())
                if (SystemClock.uptimeMillis() < scanUntil) tasks.post(this, 350L)
                return
            }
            // Catch windows created during the first few layout frames. Later
            // windows arrive through Dialog.show or WindowManager.addView.
            val windows = PaymentWindowRoots.attached().ifEmpty {
                listOfNotNull(activity.window?.decorView as? ViewGroup)
            }
            var screenSeenInScan = false
            windows.forEach { root ->
                val owner = root.context.findActivity()
                if (owner != null && owner !== activity) return@forEach
                if (owner == null && root.context.packageName != state.app.packageName) return@forEach
                watch(root)
                if (!isBusy()) {
                    if (observers[root]?.inspectNow() == true) screenSeenInScan = true
                }
            }
            if (screenState.screenAbsentTooLong(
                    screenSeenInScan, isBusy(), state.session.isInPaymentMode(), SystemClock.uptimeMillis()
                )) {
                clearPaymentScreen()
            }
            if (SystemClock.uptimeMillis() < scanUntil) tasks.post(this, 350L)
        }
    }

    fun watchActivity(activity: Activity) {
        if (closed) return
        val root = activity.window?.decorView as? ViewGroup ?: return
        val paymentHost = adapter.supports(activity)
        if (paymentHost && paymentEnabled()) {
            watch(root)
            tasks.cancel(paymentExitCheck)
            val alreadyScanning = paymentActivity?.get() === activity &&
                SystemClock.uptimeMillis() < scanUntil
            paymentActivity = WeakReference(activity)
            lastPaymentActivity = WeakReference(activity)
            if (!alreadyScanning) startWindowScan()
            if (adapter.app == PaymentApp.ALIPAY || adapter.app == PaymentApp.TAOBAO) {
                ModuleLog.d { "${adapter.app.displayName}: watching foreground Activity ${activity.javaClass.name}" }
            }
        } else if (!paymentHost && lastPaymentActivity?.get() != null) {
            schedulePaymentExitCheck()
        }
    }

    fun watchDialog(dialog: Dialog) {
        if (closed) return
        val root = dialog.window?.decorView as? ViewGroup ?: return
        val activity = root.context.findActivity() ?: paymentActivity?.get() ?: return
        if (adapter.supports(activity) && paymentEnabled()) watchWindow(root)
    }

    /** Called after a process window is attached; the callback is posted to its view. */
    fun watchWindow(root: ViewGroup) {
        if (closed || !root.isAttachedToWindow || !paymentEnabled()) return
        val activity = root.context.findActivity() ?: paymentActivity?.get() ?: return
        if (activity.application !== state.app || !adapter.supports(activity) ||
            activity.isFinishing || activity.isDestroyed) return
        watch(root)
        observers[root]?.inspectNow()
    }

    /** Reattach listeners to already open Activity and Dialog windows after hot reload. */
    fun restoreVisibleWindows() {
        tasks.onMain {
            if (closed || !paymentEnabled()) return@onMain
            for (root in PaymentWindowRoots.attached()) {
                val activity = root.context.findActivity()
                if (activity?.application === state.app) {
                    if (adapter.supports(activity)) {
                        watch(root)
                        paymentActivity = WeakReference(activity)
                        lastPaymentActivity = WeakReference(activity)
                    }
                }
            }
            if (paymentActivity?.get() != null && paymentEnabled()) startWindowScan()
        }
    }

    fun stopActivity(activity: Activity, destroyed: Boolean = false) {
        if (closed) return
        if (paymentActivity?.get() === activity) {
            paymentActivity = null
            tasks.cancel(windowScan)
            scanUntil = 0L
            schedulePaymentExitCheck()
        }
        val root = activity.window?.decorView as? ViewGroup ?: return
        val authenticationActive = state.session.isAuthenticationInProgress()
        unwatch(root, endSession = destroyed || !authenticationActive)
        if (destroyed || !authenticationActive) state.session.endSessionForActivity(activity)
    }

    fun close() {
        if (closed) return
        closed = true
        tasks.close()
        tasks.onMain {
            scanUntil = 0L
            paymentActivity = null
            lastPaymentActivity = null
            for ((root, observer) in observers) observer.removeFrom(root)
            observers.clear()
            screenState.clear()
        }
    }

    private fun watch(root: ViewGroup) {
        if (closed) return
        if (observers.containsKey(root)) return
        val observer = LayoutObserver(root)
        observers[root] = observer
        root.viewTreeObserver.addOnGlobalLayoutListener(observer)
        root.addOnAttachStateChangeListener(observer)
        observer.onGlobalLayout()
    }

    private fun unwatch(root: ViewGroup, endSession: Boolean = true) {
        observers.remove(root)?.removeFrom(root)
        if (screenState.keyboard()?.rootView === root && screenState.requested) schedulePaymentExitCheck()
        if (endSession && !screenState.prompted && screenState.keyboard()?.rootView === root) {
            screenState.clear()
            state.session.endSession(state.session.currentSessionId())
        }
    }

    private fun clearPaymentScreen() {
        screenState.clear()
        lastPaymentActivity = null
        state.session.endSession(state.session.currentSessionId())
    }

    private fun schedulePaymentExitCheck() {
        tasks.cancel(paymentExitCheck)
        if (screenState.requested || state.session.isInPaymentMode()) {
            tasks.post(paymentExitCheck, PAYMENT_EXIT_GRACE_MS)
        }
    }

    private fun startWindowScan() {
        scanUntil = SystemClock.uptimeMillis() + INITIAL_SCAN_MS
        tasks.post(windowScan)
    }

    private fun hasVisiblePaymentScreen(): Boolean = PaymentWindowRoots.attached().ifEmpty {
        listOfNotNull(lastPaymentActivity?.get()?.window?.decorView as? ViewGroup)
    }.any { root ->
        if (root.context.findActivity() == null && root.context.packageName != state.app.packageName) {
            return@any false
        }
        val activity = root.context.findActivity() ?: lastPaymentActivity?.get() ?: return@any false
        root.isAttachedToWindow && root.isShown && root.windowVisibility == View.VISIBLE &&
            activity.application === state.app &&
            !activity.isFinishing && !activity.isDestroyed &&
            adapter.supports(activity) && adapter.observe(root, activity) != null
    }

    private fun paymentEnabled(): Boolean = state.prefs.isBioPayEnabled()

    private fun isBusy(): Boolean = state.session.isAuthenticationInProgress() ||
        PasswordAutoInput.isInProgress(state.session.currentSessionId())

    private fun inspect(root: ViewGroup): Boolean {
        if (closed) return false
        if (PasswordAutoInput.isInProgress(state.session.currentSessionId())) return false
        return try {
            val activity = root.context.findActivity() ?: paymentActivity?.get() ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            val paymentHost = adapter.supports(activity)
            if (!paymentHost) {
                if (activity.window?.decorView === root) state.installEntry(activity)
                return false
            }
            if (state.session.isAuthenticationInProgress()) return false
            val password = state.prefs.activePassword() ?: return false
            val screen = adapter.observe(root, activity)
            if (screen == null) {
                if (screenState.keyboard()?.rootView === root && screenState.requested) schedulePaymentExitCheck()
                return false
            }
            tasks.cancel(paymentExitCheck)
            state.session.setInputEditText(screen.passwordInput)
            state.session.setConfirmButton(screen.confirmButton)
            if (screenState.requested) {
                val current = state.session.getCurrentKeyboardView()
                if (current?.rootView === screen.keyboard.rootView) {
                    state.session.updateKeyboardMode(screen.keyboardMode)
                }
                if (!state.session.isAuthenticationInProgress() &&
                    !PasswordAutoInput.isInProgress(state.session.currentSessionId()) &&
                    (!state.session.isInPaymentMode() || current?.isAttachedToWindow != true ||
                        current.isShown != true)
                ) {
                    if (state.flow.setupBiometricAuth(
                            screen.keyboard, password, activity,
                            startImmediately = screenState.awaitingContinue,
                            keyboardMode = screen.keyboardMode,
                            onContinue = { screenState.markPrompted(screen.keyboard) }
                        )) screenState.rememberKeyboard(screen.keyboard)
                } else if (screenState.awaitingContinue && current != null &&
                    current.rootView === screen.keyboard.rootView
                ) {
                    // The session outlived the affordance; the layout pass must redraw it.
                    state.flow.refreshContinuePrompt(current)
                }
                return true
            }
            if (screenState.keyboard() === screen.keyboard) return true
            if (state.session.isAuthenticationInProgress() ||
                PasswordAutoInput.isInProgress(state.session.currentSessionId())
            ) return true
            val now = SystemClock.uptimeMillis()
            if (!screenState.shouldAttempt(screen.keyboard, now)) return true
            ModuleLog.d { "${adapter.app.displayName}: payment password screen recognized; waiting for confirmation" }
            if (state.flow.setupBiometricAuth(
                    screen.keyboard, password, activity, keyboardMode = screen.keyboardMode,
                    onContinue = { screenState.markPrompted(screen.keyboard) }
                )) {
                screenState.markAwaitingContinue(screen.keyboard)
                ModuleLog.d { "${adapter.app.displayName}: confirmation requested" }
            } else {
                ModuleLog.d { "${adapter.app.displayName}: confirmation could not be shown" }
            }
            true
        } catch (e: Throwable) {
            ModuleLog.w(e) { "${adapter.app.displayName} payment view inspection failed" }
            false
        }
    }

    private inner class LayoutObserver(root: ViewGroup) :
        ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener, Runnable {
        private val rootRef = WeakReference(root)
        private var lastInspection = 0L
        private var lastScreenSeen = false
        private var pending = false

        override fun onGlobalLayout() {
            if (closed || pending || state.session.isAuthenticationInProgress() ||
                PasswordAutoInput.isInProgress(state.session.currentSessionId())) return
            pending = true
            // Coalesce rapid layouts while still inspecting the final layout.
            tasks.post(this, lastInspection + INSPECTION_INTERVAL_MS - SystemClock.uptimeMillis())
        }

        override fun run() {
            pending = false
            inspectNow()
        }

        fun inspectNow(): Boolean {
            val root = rootRef.get() ?: return false
            if (closed || observers[root] !== this || !root.isAttachedToWindow ||
                !root.isShown || root.windowVisibility != View.VISIBLE) {
                tasks.cancel(this)
                pending = false
                return false
            }
            if (isBusy()) {
                if (pending) tasks.cancel(this)
                pending = false
                return lastScreenSeen
            }
            val now = SystemClock.uptimeMillis()
            val delay = lastInspection + INSPECTION_INTERVAL_MS - now
            if (lastInspection != 0L && delay > 0L) {
                if (!pending) {
                    pending = true
                    tasks.post(this, delay)
                }
                // Window discovery and layout callbacks share the same short
                // interval. A deferred walk is not evidence that payment ended.
                return lastScreenSeen
            }
            if (pending) tasks.cancel(this)
            pending = false
            lastInspection = now
            lastScreenSeen = inspect(root)
            return lastScreenSeen
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            (view as? ViewGroup)?.let(::unwatch)
        }

        fun removeFrom(root: ViewGroup) {
            tasks.cancel(this)
            pending = false
            root.removeOnAttachStateChangeListener(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this)
        }
    }

    private companion object {
        const val PAYMENT_EXIT_GRACE_MS = 2_500L
        const val INITIAL_SCAN_MS = 5_000L
        const val INSPECTION_INTERVAL_MS = 250L
    }

}
