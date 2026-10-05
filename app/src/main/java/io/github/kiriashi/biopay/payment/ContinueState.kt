/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

/**
 * Which confirmation affordances a payment session currently offers.
 * Pure JVM: drawing and hit testing live in ContinuePrompt.
 */
internal class ContinueState {
    private enum class Phase { IDLE, WAITING, ABANDONED, MANUAL }

    private var phase = Phase.IDLE

    /** The keypad was handed back to the user; nothing may be drawn again for this session. */
    val manual: Boolean get() = phase == Phase.MANUAL

    /** Both exits belong on screen: retry the verification or type the password. */
    val abandoned: Boolean get() = phase == Phase.ABANDONED

    /** Returns whether the affordance must be drawn; false means bind without drawing. */
    fun request(): Boolean {
        if (phase == Phase.MANUAL) return false
        if (phase == Phase.IDLE) phase = Phase.WAITING
        return true
    }

    /** The sheet closed without entering the password. */
    fun abandon(): Boolean {
        if (phase == Phase.IDLE) return false
        phase = Phase.ABANDONED
        return true
    }

    fun enterManual() {
        phase = Phase.MANUAL
    }

    fun reset() {
        phase = Phase.IDLE
    }
}
