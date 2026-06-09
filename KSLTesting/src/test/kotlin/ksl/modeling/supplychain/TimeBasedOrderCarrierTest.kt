package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeBasedOrderCarrierTest {

    @Test
    fun `construction with no senders is empty`() {
        val m = Model("TBOC.Empty")
        val sc = SupplyChainModel(m)
        val carrier = TimeBasedOrderCarrier(sc, name = "TBOC")
        assertFalse(carrier.immediateTransportFlag)
    }

    @Test
    fun `end-to-end ships and delivers after the configured delay`() {
        val m = Model("TBOC.E2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val upstream = LeadTimeOrderFiller(sc, name = "UpSup")
        upstream.addLeadTime(item, ConstantRV(0.5))
        val carrier = TimeBasedOrderCarrier(sc, name = "TBOC")
        upstream.orderShipper = carrier

        val creator = RandomOrderCreator(sc, name = "Creator").apply {
            addItemTypeDistribution(item, amountDistribution = ConstantRV(1.0))
        }
        val deliveryTimes = mutableListOf<Double>()
        val delivered = mutableListOf<SupplyChainModel.Order>()
        val generator = object : OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "Gen",
        ) {
            init { orderFiller = upstream }
            override fun orderDelivered(order: SupplyChainModel.Order) {
                delivered += order
                deliveryTimes += time
            }
        }
        carrier.setTransportTime(generator, ConstantRV(2.0))

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()

        // Generator events at t=1..10. Lead-time fill takes 0.5, then
        // the carrier delays 2.0 more before delivery. Orders are
        // captured only after deliver().
        assertTrue(delivered.size >= 7,
            "expected >= 7 delivered orders, got ${delivered.size}")
        assertTrue(delivered.all { it.orderState === sc.orderDelivered })
        // First delivery should be at t = 1.0 + 0.5 + 2.0 = 3.5
        assertEquals(3.5, deliveryTimes[0])
    }

    @Test
    fun `contains and canShip reflect configured senders`() {
        val m = Model("TBOC.Contains")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedOrderCarrier(sc, name = "TBOC")
        val creator = RandomOrderCreator(sc, name = "Creator").apply {
            addItemTypeDistribution(item, amountDistribution = ConstantRV(1.0))
        }
        // OrderGenerator IS-A OrderSenderIfc, use one as the configured sender.
        val gen = OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "Gen",
        )
        assertFalse(carrier.contains(gen))
        carrier.setTransportTime(gen, ConstantRV(1.0))
        assertTrue(carrier.contains(gen))
    }

    @Test
    fun `immediate transport flag delivers at the ship time for unknown sender`() {
        val m = Model("TBOC.Flag")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val upstream = LeadTimeOrderFiller(sc, name = "UpSup")
        upstream.addLeadTime(item, ConstantRV(0.5))
        val carrier = TimeBasedOrderCarrier(sc, name = "TBOC")
        carrier.immediateTransportFlag = true
        upstream.orderShipper = carrier

        val creator = RandomOrderCreator(sc, name = "Creator").apply {
            addItemTypeDistribution(item, amountDistribution = ConstantRV(1.0))
        }
        val delivered = mutableListOf<SupplyChainModel.Order>()
        val deliveryTimes = mutableListOf<Double>()
        object : OrderGenerator(
            supplyChainModel = sc,
            orderCreator = creator,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "Gen",
        ) {
            init { orderFiller = upstream }
            override fun orderDelivered(order: SupplyChainModel.Order) {
                delivered += order
                deliveryTimes += time
            }
        }
        // No setTransportTime call -> sender is unknown.

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        // No carrier delay; deliver fires in the same simulation
        // instant as the fill completes.
        assertTrue(delivered.isNotEmpty())
        assertTrue(delivered.all { it.orderState === sc.orderDelivered })
        // First delivery at t = 1.0 + 0.5 + 0 = 1.5
        assertSame(sc.orderDelivered, delivered[0].orderState)
        assertEquals(1.5, deliveryTimes[0])
    }
}
