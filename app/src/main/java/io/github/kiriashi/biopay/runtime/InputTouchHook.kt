/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import android.view.MotionEvent
import android.view.View
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.payment.ContinuePrompt
import io.github.kiriashi.biopay.payment.InputMask
import io.github.libxposed.api.XposedInterface

/** Intercepts real window touches; generated keys dispatch directly to their views. */
internal object InputTouchHook {
    const val HOOK_ID = "bp_input_touch"

    fun interceptor(state: AppRuntime): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        try {
            val root = chain.thisObject as? View
            val event = chain.args[0] as? MotionEvent
            if (!state.isClosed && root != null && event != null &&
                (ContinuePrompt.blocksTouch(root, event) || InputMask.blocksTouch(root, event))
            ) {
                return@Hooker true
            }
        } catch (error: Throwable) {
            ModuleLog.w(error) { "input touch interception failed" }
        }
        chain.proceed()
    }

    fun register(xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? = runCatching {
        val decor = Class.forName("com.android.internal.policy.DecorView")
        val method = decor.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        xposed.hook(method).setId(HOOK_ID).intercept(interceptor(state))
    }.onFailure { ModuleLog.w(it) { "input touch hook failed" } }.getOrNull()
}
