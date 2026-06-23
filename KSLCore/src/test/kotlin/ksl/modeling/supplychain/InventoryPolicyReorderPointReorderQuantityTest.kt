package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventoryPolicyReorderPointReorderQuantityTest {

    private fun freshSC(): SupplyChainModel {
        val m = Model("RQTest")
        return SupplyChainModel(m)
    }

    @Test
    fun `rejects reorderQty less than 1`() {
        val sc = freshSC()
        assertThrows<IllegalArgumentException> {
            InventoryPolicyReorderPointReorderQuantity(sc, reorderPoint = 0, reorderQty = 0)
        }
    }

    @Test
    fun `rejects reorderPoint less than negative reorderQty`() {
        val sc = freshSC()
        assertThrows<IllegalArgumentException> {
            InventoryPolicyReorderPointReorderQuantity(sc, reorderPoint = -10, reorderQty = 5)
        }
    }

    @Test
    fun `getPolicyParameters returns r and Q`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointReorderQuantity(sc, reorderPoint = 3, reorderQty = 7)
        assertEquals(3.0, p.getPolicyParameters()[0])
        assertEquals(7.0, p.getPolicyParameters()[1])
    }

    @Test
    fun `setPolicyParameters updates the live values`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointReorderQuantity(sc, reorderPoint = 1, reorderQty = 2)
        p.setPolicyParameters(reorderPoint = 5, reorderQty = 10)
        assertEquals(5, p.reorderPoint)
        assertEquals(10, p.reorderQty)
    }

    @Test
    fun `separateBatchOrders defaults to false`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointReorderQuantity(sc, 3, 5)
        assertFalse(p.separateBatchOrders)
    }

    @Test
    fun `initialReorderPointDelta rejects values below 1`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointReorderQuantity(sc, 0, 5)
        assertThrows<IllegalArgumentException> { p.initialReorderPointDelta = 0 }
    }

    @Test
    fun `initialReorderQty rejects values below 1`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointReorderQuantity(sc, 0, 5)
        assertThrows<IllegalArgumentException> { p.initialReorderQty = 0 }
    }

    @Test
    fun `initialReorderQty updates parameters and recomputes reorderPoint`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointReorderQuantity(sc, 0, 5)
        p.initialReorderPointDelta = 3
        p.initialReorderQty = 10
        // delta - Q = 3 - 10 = -7
        assertEquals(-7, p.reorderPoint)
        assertEquals(10, p.reorderQty)
    }
}
