package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression for the `amountPending` leak on rejected replenishments
 * (audit finding B).  `createReplenishmentDemand` reserves
 * `amountPending += qty`; previously the only release happened on the
 * `Received` transition, so a replenishment rejected by its filler
 * left `amountPending` permanently inflated — silently raising the
 * inventory position so the inventory under-orders for the rest of
 * the run.  The leak was masked because the default rejection listener
 * throws; this test installs a graceful handler (now possible via the
 * injectable [Inventory.replenishmentRejectionListener]) and asserts
 * the reservation is released.
 *
 * Setup: an inventory whose upstream filler is a
 * [LeadTimeDemandFiller] that has **not** registered the item type, so
 * it rejects every replenishment with `ItemTypeMismatch`.  Customer
 * demand drains stock and triggers repeated reorders, each rejected.
 * With the fix, `amountPending` returns to 0 between (synchronous)
 * rejections and is 0 at replication end; without it, it grows
 * without bound.
 */
class AmountPendingReleaseTest {

    @Test
    fun `rejected replenishment releases amountPending`() {
        val m = Model("PendingRelease")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")

        // Upstream filler that rejects everything (item type never
        // registered → ItemTypeMismatch on every replenishment).
        val supplier = LeadTimeDemandFiller(sc, name = "Rejecter")

        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc, itemType = item,
            reorderPoint = 2, reorderQty = 10, initialOnHand = 5,
            name = "INV",
        )
        inv.demandFiller = supplier

        // Graceful rejection handler so the run continues instead of
        // aborting on the default fail-loud listener.
        inv.replenishmentRejectionListener =
            object : InventoryReplenishmentRejectionListener(inv) {
                override fun itemTypeMismatch(demand: SupplyChainModel.Demand) {
                    // swallow — record-and-continue
                }
            }

        // Customer demand drains stock and triggers reorders.
        val dg = DemandGenerator(
            supplyChainModel = sc, itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        dg.demandFiller = inv

        var pendingAtEnd = -1
        var ordersFired = 0.0
        inv.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(me: ModelElement) {
                pendingAtEnd = inv.amountPending
                ordersFired = inv.orderCounterCounter.value
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // Sanity: reorders actually fired (so the leak path was exercised).
        assert(ordersFired > 0.0) {
            "expected replenishment orders to fire, got $ordersFired"
        }
        // The fix: every rejected replenishment released its reservation.
        assertEquals(0, pendingAtEnd,
            "amountPending must return to 0 after rejected replenishments " +
                "(got $pendingAtEnd over $ordersFired rejected orders)")
    }
}
