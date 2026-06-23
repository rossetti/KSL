package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WarehouseEndToEndTest {

    @Test
    fun `warehouse holds an inventory and exposes its order queue`() {
        val m = Model("WH.Basic")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val w = Warehouse(sc, name = "WH")
        val inv = w.addReorderPointReorderQuantityInventory(
            type = a, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        assertEquals(a, inv.itemType)
        assertTrue(w.mightRequest(a))
        assertNotNull(w.orderQueue)
    }

    @Test
    fun `warehouse fills a one-item-type order via its internal inventory`() {
        val m = Model("WH.OneItem")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")

        // Upstream order-filler supplies replenishment orders (warehouse
        // packages replenishment demands into orders and dispatches).
        val upstreamOrderSupplier = LeadTimeOrderFiller(sc, name = "UpSup")
        upstreamOrderSupplier.addLeadTime(a, ConstantRV(0.5))

        val warehouse = Warehouse(sc, name = "WH")
        warehouse.replenishmentOrderFiller = upstreamOrderSupplier
        warehouse.addReorderPointReorderQuantityInventory(
            type = a, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )

        val captured = mutableListOf<SupplyChainModel.Order>()
        val creator = RandomOrderCreator(sc, name = "Creator").apply {
            addItemTypeDistribution(a, amountDistribution = ConstantRV(1.0))
        }
        val generator = object : OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "Gen",
        ) {
            init { orderFiller = warehouse }
            override fun orderDelivered(order: SupplyChainModel.Order) {
                captured += order
            }
        }

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()

        // Events at t=1..10; on-hand satisfies the first 5 demands
        // immediately; later demands depend on replenishment timing.
        assertTrue(captured.size >= 5, "expected >= 5 delivered orders, got ${captured.size}")
        assertTrue(captured.all { it.orderState === sc.orderDelivered })
    }

    @Test
    fun `warehouse with multiple inventories handles multi-item orders`() {
        val m = Model("WH.MultiItem")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")

        val upstreamOrderSupplier = LeadTimeOrderFiller(sc, name = "UpSup")
        upstreamOrderSupplier.addLeadTime(a, ConstantRV(0.5))
        upstreamOrderSupplier.addLeadTime(b, ConstantRV(0.5))

        val warehouse = Warehouse(sc, name = "WH")
        warehouse.replenishmentOrderFiller = upstreamOrderSupplier
        warehouse.addReorderPointReorderQuantityInventory(
            type = a, reorderPoint = 2, reorderQty = 10, initialOnHand = 10,
        )
        warehouse.addReorderPointReorderQuantityInventory(
            type = b, reorderPoint = 2, reorderQty = 10, initialOnHand = 10,
        )

        val creator = RandomOrderCreator(sc, name = "Creator").apply {
            addItemTypeDistribution(a, amountDistribution = ConstantRV(1.0))
            addItemTypeDistribution(b, amountDistribution = ConstantRV(1.0))
        }
        val captured = mutableListOf<SupplyChainModel.Order>()
        val generator = object : OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(2.0),
            timeBtwEventsRV = ConstantRV(2.0),
            name = "Gen",
        ) {
            init { orderFiller = warehouse }
            override fun orderDelivered(order: SupplyChainModel.Order) {
                captured += order
            }
        }

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()

        // Events at t=2,4,6,8,10; each order has two demands (A and B);
        // both inventories start with 10 units so the first 5 events fit.
        assertTrue(captured.size >= 4, "expected >= 4 delivered orders, got ${captured.size}")
        assertTrue(captured.all { it.orderState === sc.orderDelivered })
        assertTrue(captured.all { it.numberOfDemands == 2 })
    }
}
