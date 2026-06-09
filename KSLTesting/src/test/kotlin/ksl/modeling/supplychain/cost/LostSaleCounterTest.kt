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
 * lost-sale counter on the reject branch of `receive`.  Uses a
 * `DemandGenerator` with `permitBackLogging = false` so every
 * stockout demand is rejected at the inventory, becoming a lost
 * sale.
 *
 * Expected: of 5 demands, the first 2 fill from stock; the
 * remaining 3 stockout AND are rejected.  Stockout count = 3,
 * lost-sale count = 3.
 */
class LostSaleCounterTest {

    @Test
    fun `lostSaleCounter captures three rejected stockout demands`() {
        val m = Model("LostSale")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")

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

        // Demands explicitly do not allow backlogging — every stockout
        // becomes a lost sale.  The default DemandGenerator throws on
        // rejection (a safety check); subclass and swallow for this
        // test scenario.
        val dg = object : DemandGenerator(
            supplyChainModel = sc,
            itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        ) {
            override fun demandRejected(demand: SupplyChainModel.Demand) {
                // Lost sale — exactly what we want to count.
            }
        }
        dg.permitBackLogging = false
        dg.demandFiller = inv

        var capturedStockouts = -1.0
        var capturedLostSales = -1.0
        inv.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                capturedStockouts = inv.stockoutCounter.value
                capturedLostSales = inv.lostSaleCounter.value
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        assertEquals(3.0, capturedStockouts)
        assertEquals(3.0, capturedLostSales)
    }

    @Test
    fun `lostSaleCounter is zero when stockouts are all backlogged`() {
        // Mirror of the StockoutCounterTest setup — demands default to
        // permitBackLogging = true, so every stockout is queued (not
        // rejected) and the lost-sale counter stays at 0.
        val m = Model("NoLostSale")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")

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

        var capturedLostSales = -1.0
        inv.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                capturedLostSales = inv.lostSaleCounter.value
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        assertEquals(0.0, capturedLostSales)
    }
}
