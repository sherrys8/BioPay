package io.github.kiriashi.biopay.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueStateTest {
    @Test
    fun firstRequestCoversTheKeypadAndKeepsItCovered() {
        val state = ContinueState()

        assertFalse(state.manual)
        assertTrue(state.request())
        assertFalse(state.manual)

        // A rebuilt keypad or a cancelled attempt asks again; the cover must be redrawn, not skipped.
        assertTrue(state.request())
    }

    @Test
    fun manualEntrySilencesTheCoverUntilTheSessionEnds() {
        val state = ContinueState()
        state.request()

        state.enterManual()
        assertTrue(state.manual)
        assertFalse(state.request())
    }

    @Test
    fun aNewSessionBringsTheCoverBack() {
        val state = ContinueState()
        state.request()
        state.enterManual()

        state.reset()
        assertFalse(state.manual)
        assertTrue(state.request())
    }
}
