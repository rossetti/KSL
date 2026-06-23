package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class InventoryPolicyReorderPointOrderUpToLevelTest {

    private fun freshSC(): SupplyChainModel {
        val m = Model("RSTest")
        return SupplyChainModel(m)
    }

    @Test
    fun `rejects orderUpToPoint less than 1`() {
        val sc = freshSC()
        assertThrows<IllegalArgumentException> {
            InventoryPolicyReorderPointOrderUpToLevel(sc, reorderPoint = -1, orderUpToPoint = 0)
        }
    }

    @Test
    fun `rejects reorderPoint not less than orderUpToPoint`() {
        val sc = freshSC()
        assertThrows<IllegalArgumentException> {
            InventoryPolicyReorderPointOrderUpToLevel(sc, reorderPoint = 5, orderUpToPoint = 5)
        }
    }

    @Test
    fun `getPolicyParameters returns r and S`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointOrderUpToLevel(sc, reorderPoint = 2, orderUpToPoint = 12)
        val params = p.getPolicyParameters()
        assertEquals(2.0, params[0])
        assertEquals(12.0, params[1])
    }

    @Test
    fun `setPolicyParameters validates and updates`() {
        val sc = freshSC()
        val p = InventoryPolicyReorderPointOrderUpToLevel(sc, 1, 5)
        p.setPolicyParameters(3, 8)
        assertEquals(3, p.reorderPoint)
        assertEquals(8, p.orderUpToPoint)
        assertThrows<IllegalArgumentException> { p.setPolicyParameters(8, 8) }
    }
}
