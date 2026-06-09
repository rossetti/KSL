package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderGeneratorTest {

    @Test
    fun `mightRequest reflects the creator`() {
        val m = Model("OrderGeneratorMightRequest")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")
        val creator = RandomOrderCreator(sc).apply {
            addItemTypeDistribution(a, amountDistribution = ConstantRV(1.0))
        }
        val g = OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
        )
        assertTrue(g.mightRequest(a))
        assertFalse(g.mightRequest(b))
    }

    @Test
    fun `end-to-end simulation generates and delivers orders`() {
        val m = Model("OrderGeneratorE2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = LeadTimeOrderFiller(sc)
        filler.addLeadTime(item, ConstantRV(0.5))
        val creator = RandomOrderCreator(sc).apply {
            addItemTypeDistribution(item, amountDistribution = ConstantRV(2.0))
        }
        val delivered = mutableListOf<SupplyChainModel.Order>()
        val g = object : OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "OrderGenE2E",
        ) {
            init { orderFiller = filler }
            override fun orderDelivered(order: SupplyChainModel.Order) {
                delivered += order
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 11.0
        m.simulate()
        // Events at t=1..10; each order delivers at t+0.5 (lead time)
        assertEquals(10, delivered.size)
        assertTrue(delivered.all { it.orderState === sc.orderDelivered })
        assertTrue(delivered.all { it.isFilled })
    }

    @Test
    fun `null creator means no orders generated`() {
        val m = Model("OrderGeneratorNullCreator")
        val sc = SupplyChainModel(m)
        val filler = LeadTimeOrderFiller(sc)
        // No creator → generator fires events but never produces orders
        val g = OrderGenerator(
            supplyChainModel = sc,
            orderCreator = null,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
        )
        g.orderFiller = filler
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()
        // Just check the sim runs without error and the generator did its
        // events (eventCount reflects firings, even if no orders).
        assertTrue(g.eventCount in 1..5)
    }
}
