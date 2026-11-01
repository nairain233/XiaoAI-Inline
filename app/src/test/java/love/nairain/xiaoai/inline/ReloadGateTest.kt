package love.nairain.xiaoai.inline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadGateTest {
    @Test fun reloadWaitsForEveryInflationCallback() {
        val gate = ReloadGate()
        assertTrue(gate.begin())
        assertTrue(gate.begin())
        assertFalse(gate.retire())
        gate.end()
        assertFalse(gate.retire())
        gate.end()
        assertTrue(gate.retire())
        assertFalse(gate.begin())
    }

    @Test fun refusedReloadLeavesOldGenerationUsable() {
        val gate = ReloadGate()
        gate.begin()
        assertFalse(gate.retire())
        assertFalse(gate.retired)
        assertTrue(gate.begin())
        gate.end()
        gate.end()
        assertTrue(gate.retire())
    }

    @Test fun repeatedRetirementDoesNotReopenGeneration() {
        val gate = ReloadGate()
        assertTrue(gate.retire())
        assertTrue(gate.retire())
        assertFalse(gate.begin())
    }
}
