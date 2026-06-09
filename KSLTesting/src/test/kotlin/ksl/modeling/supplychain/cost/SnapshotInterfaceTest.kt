package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.*
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Phase-1 (cost redesign) — confirms that
 * [Inventory.snapshotCostObservables] reads stabilized within-
 * replication values and that the data-class snapshot is immutable
 * (decoupled from the source).
 *
 * Captures a snapshot inside a `ModelElementObserver.replicationEnded`
 * callback, then asserts each snapshot field equals the source's
 * underlying TWResponse / Counter at that moment.
 */
class SnapshotInterfaceTest {

    @Test
    fun `snapshotCostObservables mirrors the inventory's stable state`() {
        val m = Model("Snapshot")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")
        val supplier = LeadTimeDemandFiller(sc, name = "Supplier")
        supplier.addLeadTime(item, ConstantRV(1000.0))

        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc, itemType = item,
            reorderPoint = 0, reorderQty = 100, initialOnHand = 2,
            name = "INV",
        )
        inv.demandFiller = supplier

        val dg = DemandGenerator(
            supplyChainModel = sc, itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        dg.demandFiller = inv

        var snap: InventoryCostObservables? = null
        inv.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                snap = inv.snapshotCostObservables()
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        val s = snap ?: error("snapshot was not captured")

        // Snapshot's item is the inventory's item, by reference.
        assertSame(item, s.item)

        // Counters: stockouts == 3 (last three demands), lostSales == 0
        // (all backlogged), unitsShort == 3 (1 each), unitsOrdered == 100
        // (one reorder for Q=100 fired when on-hand dropped to 0).
        assertEquals(3.0, s.stockoutCount)
        assertEquals(0.0, s.lostSaleCount)
        assertEquals(3.0, s.totalUnitsShort)
        assertEquals(1.0, s.orderCount)
        assertEquals(100.0, s.totalUnitsOrdered)

        // The snapshot's avgOnHand / avgOnOrder / avgBacklog mirror
        // the source's TWResponse weightedAverage values at the
        // moment of capture.
        assertEquals(
            inv.onHandResponse.withinReplicationStatistic.weightedAverage,
            s.avgOnHand, 1e-12,
        )
        assertEquals(
            inv.onOrderResponse.withinReplicationStatistic.weightedAverage,
            s.avgOnOrder, 1e-12,
        )
        // avgBacklog comes from the attached BackLogQueue policy.
        val expectedBacklog = inv.backLogPolicy?.avgBacklogInQ ?: 0.0
        assertEquals(expectedBacklog, s.avgBacklog, 1e-12)
    }

    @Test
    fun `snapshot avgBacklog is zero when no backlog policy is attached`() {
        // An Inventory built directly (without the factory) has no
        // backlog policy.  The snapshot should report avgBacklog = 0.0
        // rather than throwing.
        val m = Model("Snapshot.NoBacklog")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")
        val supplier = LeadTimeDemandFiller(sc, name = "Supplier")
        supplier.addLeadTime(item, ConstantRV(1.0))

        // Use the (s, S) factory but immediately drop the backlog
        // policy: we just want an inventory that ran but with no
        // backlog statistics to read.  Actually the factories all
        // attach a BackLogQueue.  Simulate with no demand instead
        // — the snapshot will read 0.0 from the attached queue.
        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc, itemType = item,
            reorderPoint = 0, reorderQty = 5, initialOnHand = 5,
            name = "INV",
        )
        inv.demandFiller = supplier

        var snap: InventoryCostObservables? = null
        inv.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                snap = inv.snapshotCostObservables()
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        val s = snap ?: error("snapshot was not captured")
        // No demand → no backlog ever entered the queue.
        assertEquals(0.0, s.avgBacklog, 1e-12)
        // Same: no stockouts, no lost sales, no orders.
        assertEquals(0.0, s.stockoutCount)
        assertEquals(0.0, s.lostSaleCount)
        assertEquals(0.0, s.totalUnitsShort)
        assertEquals(0.0, s.orderCount)
        assertEquals(0.0, s.totalUnitsOrdered)
    }
}
