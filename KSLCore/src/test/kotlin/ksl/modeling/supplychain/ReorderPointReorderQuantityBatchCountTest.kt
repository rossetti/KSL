package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.DemandGenerator
import ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointReorderQuantity
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  An (r, Q) policy must order the fewest whole batches that lift the inventory position strictly
 *  above r: floor((r - IP)/Q) + 1 of them. The old rule, ceil((r - IP)/Q), is one batch short
 *  whenever r - IP is a positive multiple of Q, leaving the position exactly at r. Unit demand never
 *  exposes it, because the position then reaches r without passing it; a lot of 10 against r = 10,
 *  Q = 5 and a position of 15 does.
 */
class ReorderPointReorderQuantityBatchCountTest {

    private fun unitsOrderedAfterOneLot(lot: Int, separateBatchOrders: Boolean): Double {
        val model = Model("reorder-rule", autoCSVReports = false)
        val sc = SupplyChainModel(model, name = "SC")
        val net = MultiEchelonNetwork(sc, "Net")
        // A lead time longer than the run, so nothing arrives and only the ordering is observed.
        val item = net.addItemType("Item", ConstantRV(1000.0))
        val ihp = net.addInventoryHoldingPoint("Loc")
        val inventory = ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 10, reorderQty = 5, initialOnHand = 15, name = "Inv"
        )
        (inventory.inventoryPolicy as InventoryPolicyReorderPointReorderQuantity).separateBatchOrders =
            separateBatchOrders
        net.attachIHPToExternalSupplier(ihp)
        val customers = DemandGenerator(
            sc, item, ConstantRV(1.0), ConstantRV(1.0), maxNumberOfEvents = 1, name = "Cust"
        )
        customers.setAmountDistribution(ConstantRV(lot.toDouble()))
        net.attachDemandGenerator(ihp, customers)
        model.numberOfReplications = 1
        model.lengthOfReplication = 2.0
        model.simulate()
        return inventory.totalUnitsOrdered.acrossReplicationStatistic.average
    }

    @Test
    fun ordersEnoughBatchesToLiftThePositionAboveR() {
        for (separate in listOf(false, true)) {
            for (lot in 1..20) {
                val ip = 15 - lot
                val expected = if (ip > 10) 0 else ((10 - ip) / 5 + 1) * 5
                assertEquals(
                    expected.toDouble(), unitsOrderedAfterOneLot(lot, separate),
                    "lot $lot, separateBatchOrders = $separate"
                )
            }
        }
    }
}
