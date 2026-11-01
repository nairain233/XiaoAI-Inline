package love.nairain.xiaoai.inline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEpochTest {
    @Test fun lateResponseCannotReplaceNewerResponse() {
        val epoch = SuggestionEpoch()
        val first = epoch.next()
        val second = epoch.next()
        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
    }

    @Test fun finishingInputInvalidatesPendingInflation() {
        val epoch = SuggestionEpoch()
        val pendingInflation = epoch.next()
        epoch.next()
        assertFalse(epoch.isCurrent(pendingInflation))
    }
}
