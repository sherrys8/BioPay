package io.github.kiriashi.biopay.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueStateTest {
    @Test
    fun firstRequestWaitsAndKeepsOfferingTheButton() {
        val state = ContinueState()

        assertFalse(state.abandoned)
        assertTrue(state.request())
        assertFalse(state.manual)

        // A rebuilt keypad asks again; the affordance must be redrawn, not suppressed.
        assertTrue(state.request())
        assertFalse(state.abandoned)
    }

    @Test
    fun abandoningAddsTheManualEntryAndSurvivesAnotherFailure() {
        val state = ContinueState()
        state.request()

        assertTrue(state.abandon())
        assertTrue(state.abandoned)
        assertTrue(state.request())

        // The user may keep cancelling; both exits stay available.
        assertTrue(state.abandon())
        assertTrue(state.abandoned)
    }

    @Test
    fun manualModeSilencesTheAffordanceUntilTheSessionEnds() {
        val state = ContinueState()
        state.request()
        state.abandon()

        state.enterManual()
        assertTrue(state.manual)
        assertFalse(state.request())
        assertFalse(state.abandoned)
    }

    @Test
    fun manualModeStillLetsTheVolumeKeyBringTheExitsBack() {
        val state = ContinueState()
        state.request()
        state.enterManual()

        // The volume key opens the sheet directly; if it fails, the exits return.
        assertTrue(state.abandon())
        assertTrue(state.abandoned)
        assertTrue(state.request())
    }

    @Test
    fun nothingIsOfferedBeforeTheFirstRequest() {
        val state = ContinueState()

        assertFalse(state.abandon())
        assertFalse(state.abandoned)

        state.request()
        state.reset()
        assertFalse(state.abandoned)
        assertTrue(state.request())
    }
}
