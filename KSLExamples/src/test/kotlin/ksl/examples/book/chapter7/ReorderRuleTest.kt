package ksl.examples.book.chapter7

import ksl.examples.general.models.inventory.ItemType
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ksl.examples.general.bookbundle.InventoryFillerIfc as BundleFillerIfc
import ksl.examples.general.bookbundle.RQInventory as BundleRQInventory
import ksl.examples.general.models.inventory.InventoryFillerIfc as GeneralFillerIfc
import ksl.examples.general.models.inventory.RQInventory as GeneralRQInventory

/**
 *  Every example (r, Q) class must order the fewest whole batches that lift the inventory position
 *  strictly above r: floor((r - IP)/Q) + 1. The old ceiling rule was one batch short whenever r - IP
 *  was a multiple of Q. Each inventory starts at the position under test with a replenisher that
 *  never delivers, so the order placed at initialization is the one being checked.
 */
class ReorderRuleTest {

    private val r = 10
    private val q = 5

    private fun expectedOrder(ip: Int): Int = if (ip > r) 0 else ((r - ip) / q + 1) * q

    private fun orderedAtStart(ip: Int, kind: String): Int {
        var ordered = 0
        val model = Model("reorder-rule-$kind-$ip", autoCSVReports = false)
        when (kind) {
            "chapter7" -> RQInventory(
                model, reorderPt = r, reorderQty = q, initialOnHand = ip,
                replenisher = object : InventoryFillerIfc {
                    override fun fillInventory(demand: Int) { ordered += demand }
                },
                name = "Item"
            )
            "bundle" -> BundleRQInventory(
                model, reorderPt = r, reorderQty = q, initialOnHand = ip,
                replenisher = object : BundleFillerIfc {
                    override fun fillInventory(demand: Int) { ordered += demand }
                },
                name = "Item"
            )
            else -> GeneralRQInventory(
                model, ItemType("Widget"), reorderPt = r, reorderQty = q, initialOnHand = ip,
                inventoryFiller = GeneralFillerIfc { demand -> ordered += demand.originalAmount },
                name = "Item"
            )
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 1.0
        model.simulate()
        return ordered
    }

    @Test
    fun everyExampleOrdersEnoughToLiftThePositionAboveR() {
        for (kind in listOf("chapter7", "bundle", "general")) {
            for (ip in 0..15) {
                assertEquals(expectedOrder(ip), orderedAtStart(ip, kind), "$kind, starting position $ip")
            }
        }
    }

    @Test
    fun theGeneralClassReachesNegativeReorderPointsThroughRDelta() {
        val model = Model("rdelta", autoCSVReports = false)
        val inv = GeneralRQInventory(
            model, ItemType("Widget"), reorderPt = 1, reorderQty = 5, initialOnHand = 6,
            inventoryFiller = GeneralFillerIfc { }, name = "Item"
        )
        inv.initialReorderPointDelta = 2
        assertEquals(-3, inv.initialReorderPoint)
        // Once RDelta is in use, setting Q holds r + Q fixed, so the pair is valid in either order.
        inv.initialReorderQty = 3
        assertEquals(-1, inv.initialReorderPoint)
        assertEquals(2, inv.initialReorderPointDelta)
        // Setting r directly returns to holding r fixed when Q changes.
        inv.initialReorderPoint = 4
        inv.initialReorderQty = 6
        assertEquals(4, inv.initialReorderPoint)
        assertEquals(10, inv.initialReorderPointDelta)
    }
}
