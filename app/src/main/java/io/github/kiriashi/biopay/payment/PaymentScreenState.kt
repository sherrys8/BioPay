/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One visible payment screen shows the affordance, then opens the sheet on a tap. */
internal class PaymentScreenState<Keyboard : Any> {
    private enum class Phase { SEARCHING, WAITING_CONTINUE, PROMPT_USED }

    private var phase = Phase.SEARCHING
    private var keyboardRef: WeakReference<Keyboard>? = null
    private var absentSince = 0L
    private val lastAttempt = WeakHashMap<Keyboard, Long>()

    /** The sheet has already been opened for this screen. */
    val prompted: Boolean get() = phase == Phase.PROMPT_USED

    /** The screen was recognized, whether it waits for a tap or is already authenticating. */
    val requested: Boolean get() = phase != Phase.SEARCHING

    val awaitingContinue: Boolean get() = phase == Phase.WAITING_CONTINUE

    fun keyboard(): Keyboard? = keyboardRef?.get()

    fun rememberKeyboard(keyboard: Keyboard) {
        keyboardRef = WeakReference(keyboard)
    }

    fun markAwaitingContinue(keyboard: Keyboard) {
        rememberKeyboard(keyboard)
        phase = Phase.WAITING_CONTINUE
    }

    fun markPrompted(keyboard: Keyboard) {
        rememberKeyboard(keyboard)
        phase = Phase.PROMPT_USED
    }

    fun shouldAttempt(keyboard: Keyboard, now: Long): Boolean {
        if (now - (lastAttempt[keyboard] ?: 0L) < 1_000L) return false
        lastAttempt[keyboard] = now
        return true
    }

    fun screenAbsentTooLong(seen: Boolean, busy: Boolean, hasSession: Boolean, now: Long): Boolean {
        if (seen || busy) {
            absentSince = 0L
            return false
        }
        if (absentSince == 0L) {
            absentSince = now
            return false
        }
        return now - absentSince > 2_500L && (requested || hasSession)
    }

    fun clear() {
        phase = Phase.SEARCHING
        keyboardRef = null
        absentSince = 0L
        lastAttempt.clear()
    }
}
