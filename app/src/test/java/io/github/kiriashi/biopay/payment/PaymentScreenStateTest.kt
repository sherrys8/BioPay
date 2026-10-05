package io.github.kiriashi.biopay.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenStateTest {
    @Test
    fun cancelKeepsAutomaticPromptConsumedUntilScreenEnds() {
        val screen = PaymentScreenState<Any>()
        val keyboard = Any()

        screen.markPrompted(keyboard)
        assertTrue(screen.prompted)
        assertSame(keyboard, screen.keyboard())

        screen.rememberKeyboard(Any())
        assertTrue(screen.prompted)

        screen.clear()
        assertFalse(screen.prompted)
        assertTrue(screen.keyboard() == null)
    }

    @Test
    fun retryAndExitGraceAreScopedToOneScreen() {
        val screen = PaymentScreenState<Any>()
        val keyboard = Any()

        assertTrue(screen.shouldAttempt(keyboard, 1_000L))
        assertFalse(screen.shouldAttempt(keyboard, 1_500L))
        assertTrue(screen.shouldAttempt(keyboard, 2_001L))

        screen.markPrompted(keyboard)
        assertFalse(screen.screenAbsentTooLong(false, false, false, 3_000L))
        assertFalse(screen.screenAbsentTooLong(false, true, false, 5_600L))
        assertFalse(screen.screenAbsentTooLong(false, false, false, 5_700L))
        assertTrue(screen.screenAbsentTooLong(false, false, false, 8_201L))

        screen.clear()
        assertTrue(screen.shouldAttempt(keyboard, 9_000L))
    }

    @Test
    fun awaitingConfirmationCountsAsRequestedWithoutConsumingTheSheet() {
        val screen = PaymentScreenState<Any>()
        val keyboard = Any()

        assertFalse(screen.requested)
        assertFalse(screen.awaitingContinue)

        screen.markAwaitingContinue(keyboard)
        assertTrue(screen.requested)
        assertTrue(screen.awaitingContinue)
        assertFalse(screen.prompted)
        assertSame(keyboard, screen.keyboard())

        // A screen that only shows the confirmation must still end its session when it disappears.
        assertFalse(screen.screenAbsentTooLong(false, false, false, 1_000L))
        assertTrue(screen.screenAbsentTooLong(false, false, false, 3_600L))

        screen.markPrompted(keyboard)
        assertTrue(screen.prompted)
        assertTrue(screen.requested)
        assertFalse(screen.awaitingContinue)

        screen.clear()
        assertFalse(screen.requested)
    }

    @Test
    fun usedSheetNeverFallsBackToWaitingForConfirmation() {
        val screen = PaymentScreenState<Any>()

        screen.markPrompted(Any())
        assertFalse(screen.awaitingContinue)
        assertTrue(screen.requested)

        // A rebuilt keypad on a screen whose sheet was already used must bind without a new request.
        screen.rememberKeyboard(Any())
        assertFalse(screen.awaitingContinue)
    }
}
