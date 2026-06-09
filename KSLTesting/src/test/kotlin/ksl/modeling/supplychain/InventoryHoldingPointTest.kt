package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class InventoryHoldingPointTest {

    private fun build(): Triple<SupplyChainModel, ItemType, InventoryHoldingPoint> {
        val m = Model("IHPTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val ihp = InventoryHoldingPoint(sc, name = "IHP")
        return Triple(sc, item, ihp)
    }

    @Test
    fun `level getter and setter`() {
        val (_, _, ihp) = build()
        assertEquals(0, ihp.level)
        ihp.level = 3
        assertEquals(3, ihp.level)
    }

    @Test
    fun `unavailable IHP rejects with FillerUnavailable`() {
        val (sc, item, ihp) = build()
        ihp.addReorderPointReorderQuantityInventory(item, 1, 5, initialOnHand = 10)
        // Force unavailable.
        val d = sc.createDemand(item, 1)
        // We need to drive the demand to a state where receive is legal.
        // The Inventory's receive() in our port handles unavailable, but
        // the IHP overrides it. Verify the IHP's unavailable handling:
        val unavailableIhp = object : InventoryHoldingPoint(
            sc, initialAvailability = false, name = "UIHP",
        ) {}
        unavailableIhp.addReorderPointReorderQuantityInventory(item, 1, 5, initialOnHand = 10)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        unavailableIhp.receive(d)
        assertSame(sc.rejected, d.demandState)
        assertEquals(DemandStatusCode.FillerUnavailable, d.status)
    }

    @Test
    fun `unknown item type rejected with ItemTypeMismatch`() {
        val (sc, _, ihp) = build()
        val other = ItemType(sc, name = "Other")
        val d = sc.createDemand(other, 1)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        ihp.receive(d)
        assertSame(sc.rejected, d.demandState)
        assertEquals(DemandStatusCode.ItemTypeMismatch, d.status)
    }
}
