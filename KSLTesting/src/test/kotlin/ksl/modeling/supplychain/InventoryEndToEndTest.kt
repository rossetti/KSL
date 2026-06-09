package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end simulations putting the entire 3b inventory layer
 * together: customer DemandGenerator -> Inventory with (r, Q) policy
 * + BackLogQueue -> LeadTimeDemandFiller (the supplier).
 */
class InventoryEndToEndTest {

    @Test
    fun `factory createReorderPointReorderQuantityInventory builds a working inventory`() {
        val m = Model("E2E.Factory")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc,
            itemType = item,
            reorderPoint = 2,
            reorderQty = 5,
            initialOnHand = 5,
            name = "INV",
        )
        assertSame(item, inv.itemType)
        // The factory attaches a BackLogQueue automatically.
        assertTrue(inv.allowBackLogging)
        assertSame(inv, inv.backLogPolicy?.let { _ -> inv })
    }

    @Test
    fun `factory createReorderPointOrderUpToLevelInventory works`() {
        val m = Model("E2E.RSFactory")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-2")
        val inv = Inventory.createReorderPointOrderUpToLevelInventory(
            parent = sc,
            itemType = item,
            reorderPoint = 1,
            orderUpToPoint = 8,
            initialOnHand = 8,
            name = "RSInv",
        )
        assertSame(item, inv.itemType)
        assertTrue(inv.allowBackLogging)
    }

    @Test
    fun `inventory replenishes via supplier through full simulation`() {
        val m = Model("E2E.Replenish")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")

        // Supplier with constant lead time.
        val supplier = LeadTimeDemandFiller(sc, name = "Supplier")
        supplier.addLeadTime(item, ConstantRV(1.0))

        // Inventory starts with 5 units, reorder point 2, reorder quantity 5.
        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc,
            itemType = item,
            reorderPoint = 2,
            reorderQty = 5,
            initialOnHand = 5,
            name = "INV",
        )
        inv.demandFiller = supplier

        // Customer that consumes 1 unit per arrival, every 1 time unit.
        val customer = object : DemandGenerator(
            sc, item, ConstantRV(1.0), ConstantRV(1.0),
            name = "Cust",
        ) {
            init { demandFiller = inv }
        }

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Sanity checks:
        // - The replenishment counter was incremented at least once.
        assertTrue(inv.orderCounterCounter.value >= 1.0)
        // - Some demands were filled (the within-rep first-fill-rate
        //   weighted statistic reflects that).
        assertTrue(inv.firstFillRateWithinReplication.count >= 1.0)
    }
}
