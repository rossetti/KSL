package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InventoryHolderAbstractTest {

    /** Minimal concrete subclass — receive/fillDemand delegate to the inventory. */
    private class TestHolder(
        parent: ModelElement,
        name: String = "TestHolder",
    ) : InventoryHolderAbstract(parent, true, name) {
        override fun receive(demand: SupplyChainModel.Demand) {
            myInventory[demand.itemType]?.receive(demand)
        }
        override fun fillDemand(demand: SupplyChainModel.Demand) {
            myInventory[demand.itemType]?.fillDemand(demand)
        }
    }

    private fun build(): Triple<SupplyChainModel, ItemType, TestHolder> {
        val m = Model("IHATest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val holder = TestHolder(sc)
        return Triple(sc, item, holder)
    }

    @Test
    fun `default has no inventories and no filler`() {
        val (_, _, h) = build()
        assertEquals(0, h.numberOfItemTypes)
        assertTrue(h.itemTypes.isEmpty())
        assertNull(h.demandFiller)
        assertNull(h.demandFillerFinder)
        assertNotNull(h.replenishmentRequester)
    }

    @Test
    fun `addReorderPointReorderQuantityInventory adds an inventory`() {
        val (_, item, h) = build()
        val inv = h.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5,
        )
        assertEquals(1, h.numberOfItemTypes)
        assertSame(inv, h.getInventory(item))
        assertSame(inv, h.getInventoryInfo(item))
        assertTrue(item in h.itemTypes)
        assertTrue(h.mightRequest(item))
    }

    @Test
    fun `cannot add two inventories for the same item type`() {
        val (_, item, h) = build()
        h.addReorderPointReorderQuantityInventory(item, 1, 5)
        assertThrows<IllegalArgumentException> {
            h.addReorderPointReorderQuantityInventory(item, 2, 6)
        }
    }

    @Test
    fun `removeInventory drops the item type`() {
        val (_, item, h) = build()
        h.addReorderPointReorderQuantityInventory(item, 1, 5)
        h.removeInventory(item)
        assertEquals(0, h.numberOfItemTypes)
        assertNull(h.getInventory(item))
    }

    @Test
    fun `canFillItemType and determineRequestStatus delegate to inventory`() {
        val (sc, item, h) = build()
        h.addReorderPointReorderQuantityInventory(item, 1, 5, initialOnHand = 0)
        val d = sc.createDemand(item, 1)
        assertTrue(h.canFillItemType(item))
        assertTrue(h.canFillItemType(d))
        // Factory attaches a backlog policy, so a backloggable demand
        // is accepted even with zero on-hand.
        assertFalse(h.willReject(d))
    }

    @Test
    fun `aggregate response delegation works`() {
        val (_, _, h) = build()
        // Just verify the properties are non-null after construction.
        assertNotNull(h.aggregateOnHandInventory)
        assertNotNull(h.aggregateAmountOnOrder)
        assertNotNull(h.aggregateAmountBackOrdered)
        assertNotNull(h.aggregateNumberBackOrdered)
        assertNotNull(h.aggregateAvgFirstFillRate)
        assertNotNull(h.aggregateAvgCustomerWaitTime)
        assertNotNull(h.aggregateNumberOfReplenishmentDemands)
    }

    @Test
    fun `mightRequest is false for an unknown type`() {
        val (sc, _, h) = build()
        val other = ItemType(sc, name = "Other")
        assertFalse(h.mightRequest(other))
    }
}
