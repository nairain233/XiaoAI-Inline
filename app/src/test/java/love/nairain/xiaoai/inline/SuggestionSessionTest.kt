package love.nairain.xiaoai.inline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionSessionTest {
    @Test fun dismissalRejectsInFlightAndSubsequentResponses() {
        val session = SuggestionSession()
        val pending = session.nextResponse()!!
        session.dismiss()
        assertFalse(session.accepts(pending))
        assertNull(session.nextResponse())
    }

    @Test fun ordinaryClearAndViewRecreationDoNotUndoDismissal() {
        val session = SuggestionSession()
        session.dismiss()
        session.invalidate()
        assertTrue(session.dismissed)
        assertNull(session.nextResponse())
    }

    @Test fun newInputOrExplicitRequestRestoresSuggestionsWithoutRevivingOldCallbacks() {
        val session = SuggestionSession()
        val previous = session.nextResponse()!!
        session.dismiss()
        session.reset()
        val current = session.nextResponse()
        assertNotNull(current)
        assertFalse(session.accepts(previous))
        assertTrue(session.accepts(current!!))
    }

    @Test fun newerResponseInvalidatesOlderInflation() {
        val session = SuggestionSession()
        val previous = session.nextResponse()!!
        val current = session.nextResponse()!!
        assertFalse(session.accepts(previous))
        assertTrue(session.accepts(current))
    }

    @Test fun hidingKeyboardInvalidatesPendingInflation() {
        val session = SuggestionSession()
        val pending = session.nextResponse()!!
        session.invalidate()
        assertFalse(session.accepts(pending))
        assertFalse(session.dismissed)
    }
}
