package love.nairain.xiaoai.inline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionDeliveryTest {
    @Test fun unlockResponseArrivingBeforeKeyboardReturnsIsDeliveredOnce() {
        val delivery = activeView()
        delivery.hideView()
        delivery.defer(listOf("unlocked-login"))
        assertTrue(delivery.active)
        assertNull(delivery.takePending())
        delivery.startView()
        assertEquals(listOf("unlocked-login"), delivery.takePending())
        assertNull(delivery.takePending())
    }

    @Test fun windowHiddenAfterUnlockResponseDoesNotDiscardIt() {
        val delivery = activeView()
        delivery.hideView()
        delivery.defer(listOf("unlocked-login"))
        delivery.hideView()
        delivery.startView()
        assertEquals(listOf("unlocked-login"), delivery.takePending())
    }

    @Test fun latestResponseReplacesEarlierLockedResponseWhileHidden() {
        val delivery = activeView()
        delivery.hideView()
        delivery.defer(listOf("locked-vault"))
        delivery.defer(listOf("unlocked-login"))
        delivery.startView()
        assertEquals(listOf("unlocked-login"), delivery.takePending())
    }

    @Test fun finishingInputCannotReviveResponseFromPreviousField() {
        val delivery = activeView()
        delivery.hideView()
        delivery.defer(listOf("previous-field"))
        delivery.hideView(finishingInput = true)
        assertFalse(delivery.active)
        delivery.defer(listOf("late-previous-response"))
        delivery.startInput()
        delivery.startView()
        assertNull(delivery.takePending())
    }

    @Test fun newFieldOrRequestDropsDeferredResponse() {
        val delivery = activeView()
        delivery.hideView()
        delivery.defer(listOf("previous-field"))
        delivery.startInput()
        delivery.startView()
        assertNull(delivery.takePending())
    }

    @Test fun emptyResponseOrDisableClearsDeferredSuggestions() {
        val delivery = activeView()
        delivery.hideView()
        delivery.defer(listOf("unlocked-login"))
        delivery.clearPending()
        delivery.startView()
        assertNull(delivery.takePending())
    }

    @Test fun restoredViewDoesNotAcceptResponsesFromRetiredGeneration() {
        val delivery = activeView()
        delivery.defer(listOf("old-generation"))
        delivery.restoreView(true)
        assertTrue(delivery.visible)
        assertFalse(delivery.active)
        assertNull(delivery.takePending())
        delivery.defer(listOf("late-old-generation"))
        delivery.startInput()
        assertNull(delivery.takePending())
    }

    @Test fun hiddenInflationIsInvalidatedButFreshResponseCanResume() {
        val delivery = activeView()
        val session = SuggestionSession()
        val oldToken = session.nextResponse()!!
        delivery.hideView()
        session.invalidate()
        assertFalse(session.accepts(oldToken))
        delivery.defer(listOf("unlocked-login"))
        delivery.startView()
        assertEquals(listOf("unlocked-login"), delivery.takePending())
        val newToken = session.nextResponse()!!
        assertTrue(session.accepts(newToken))
        assertFalse(session.accepts(oldToken))
    }

    private fun activeView() = SuggestionDelivery<String>().apply {
        startInput()
        startView()
    }
}
