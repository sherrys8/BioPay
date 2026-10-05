/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

/**
 * Whether a payment session still shows the keypad cover.
 * Pure JVM: drawing and hit testing live in ContinuePrompt.
 */
internal class ContinueState {
    private enum class Phase { IDLE, COVERED, MANUAL }

    private var phase = Phase.IDLE

    /** The user asked to type the password; the cover must never come back for this session. */
    val manual: Boolean get() = phase == Phase.MANUAL

    /** Returns whether the cover must be drawn; false means bind without covering. */
    fun request(): Boolean {
        if (phase == Phase.MANUAL) return false
        if (phase == Phase.IDLE) phase = Phase.COVERED
        return true
    }

    fun enterManual() {
        phase = Phase.MANUAL
    }

    /** The key gesture asked for verification again, so this session is no longer manual-only. */
    fun rearm() {
        if (phase == Phase.MANUAL) phase = Phase.COVERED
    }

    fun reset() {
        phase = Phase.IDLE
    }
}
