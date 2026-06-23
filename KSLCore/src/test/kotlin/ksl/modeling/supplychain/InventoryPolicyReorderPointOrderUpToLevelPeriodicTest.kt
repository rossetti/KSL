package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class InventoryPolicyReorderPointOrderUpToLevelPeriodicTest {

    private fun freshSC(): SupplyChainModel {
        val m = Model("RSPeriodicTest")
        return SupplyChainModel(m)
    }

    @Test
    fun `rejects nonpositive reviewPeriod`() {
        val sc = freshSC()
        assertThrows<IllegalArgumentException> {
            InventoryPolicyReorderPointOrderUpToLevelPeriodic(
                sc, reorderPoint = 0, orderUpToPoint = 5,
                reviewPeriod = 0.0,
            )
        }
    }

    @Test
    fun `rejects negative initialReviewTime`() {
        val sc = freshSC()
        assertThrows<IllegalArgumentException> {
            InventoryPolicyReorderPointOrderUpToLevelPeriodic(
                sc, reorderPoint = 0, orderUpToPoint = 5,
                reviewPeriod = 1.0, initialReviewTime = -0.1,
            )
        }
    }

    @Test
    fun `getPolicyParameters returns the four values in order`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointOrderUpToLevelPeriodic(
            sc, reorderPoint = 1, orderUpToPoint = 7,
            reviewPeriod = 2.5, initialReviewTime = 0.5,
        )
        val params = p.getPolicyParameters()
        assertEquals(1.0, params[0])
        assertEquals(7.0, params[1])
        assertEquals(2.5, params[2])
        assertEquals(0.5, params[3])
    }

    @Test
    fun `setInitialPolicyParameters with two values keeps prior period and first time`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointOrderUpToLevelPeriodic(
            sc, reorderPoint = 1, orderUpToPoint = 7,
            reviewPeriod = 2.5, initialReviewTime = 0.5,
        )
        p.setInitialPolicyParameters(doubleArrayOf(2.0, 9.0))
        // After: r=2, S=9, period=2.5 (preserved), first=0.5 (preserved)
        // The current state values aren't updated until beforeReplication;
        // verify the stored initial-parameters vector instead.
        val initParams = p.getInitialPolicyParameters()
        assertEquals(2.0, initParams[0])
        assertEquals(9.0, initParams[1])
        assertEquals(2.5, initParams[2])
        assertEquals(0.5, initParams[3])
    }
}
