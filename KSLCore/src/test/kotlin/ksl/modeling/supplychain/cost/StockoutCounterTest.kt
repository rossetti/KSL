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

/**
 * Phase-1 (cost redesign) — confirms that [Inventory] fires the
 * stockout-event counters at the right places and that a
 * [ModelElementObserver] attached to the inventory sees stabilized
 * values in its `replicationEnded` callback.
 *
 * Setup: a single (s, Q) inventory with `initialOnHand = 2`,
 * `reorderPoint = 0`, `reorderQty = 100`, and a deterministic
 * customer demand-generator firing 1 unit at t = 1, 2, 3, 4, 5.
 * The upstream supplier's lead time is 1000.0, so no replenishment
 * arrives during the 5.5-time-unit run.  The first two demands
 * fill from stock; the last three are stockouts and get backlogged
 * (this inventory has `allowBackLogging = true` by virtue of the
 * factory).
 *
 * Expected stockout count: 3.  Each stockout is short by 1 unit,
 * so totalUnitsShort = 3.
 */
class StockoutCounterTest {

    @Test
    fun `stockoutCounter and totalUnitsShort capture three backlogged stockouts`() {
        val (m, inv) = buildStockoutScenario(name = "Stockout")

        // Observer captures the stockout counters at the source's
        // REPLICATION_ENDED.  Phase 1 exercises the observer pattern
        // even though no CostFormulation yet exists.
        var capturedStockouts = -1.0
        var capturedUnitsShort = -1.0
        inv.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                capturedStockouts = inv.stockoutCounter.value
                capturedUnitsShort = inv.totalUnitsShort.value
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        // 5 demands of size 1, first two fill from initial stock of 2.
        // Remaining three stock out and get backlogged.
        assertEquals(3.0, capturedStockouts)
        // Each stockout was short by exactly 1 unit (demand was 1 unit
        // each, on-hand was 0 at each stockout instant).
        assertEquals(3.0, capturedUnitsShort)
    }

    private fun buildStockoutScenario(
        name: String,
    ): Pair<Model, Inventory> {
        val m = Model(name)
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")

        // Upstream supplier with a huge lead time so replenishment
        // never arrives during the test horizon.
        val supplier = LeadTimeDemandFiller(sc, name = "Supplier")
        supplier.addLeadTime(item, ConstantRV(1000.0))

        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc,
            itemType = item,
            reorderPoint = 0,
            reorderQty = 100,
            initialOnHand = 2,
            name = "INV",
        )
        inv.demandFiller = supplier

        val dg = DemandGenerator(
            supplyChainModel = sc,
            itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        dg.demandFiller = inv

        return m to inv
    }
}
