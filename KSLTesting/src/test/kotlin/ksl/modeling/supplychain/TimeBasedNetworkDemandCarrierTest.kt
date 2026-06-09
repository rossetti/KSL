package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeBasedNetworkDemandCarrierTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    @Test
    fun `canShip false until both filler and sender pair are configured`() {
        val m = Model("TBNDC.CanShip")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "TBNDC")
        val sender = TestSender("S1")
        val filler = NoOpDemandFiller()
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        d.setFiller(filler)
        assertFalse(carrier.canShip(d))
        carrier.setTransportTime(filler, sender, ConstantRV(1.0))
        assertTrue(carrier.canShip(d))
    }

    @Test
    fun `end-to-end ships and delivers after the configured delay`() {
        val m = Model("TBNDC.E2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "TBNDC")
        val sender = TestSender("S1")
        val filler = NoOpDemandFiller(name = "F1")
        carrier.setTransportTime(filler, sender, ConstantRV(2.0))

        val captured = mutableListOf<SupplyChainModel.Demand>()
        NetworkKickoff(sc, item, sender, filler, carrier, captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertEquals(2.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `attachDemandSenderToAllFillers wires a zero-delay edge`() {
        val m = Model("TBNDC.AttachAll")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "TBNDC")
        val filler = NoOpDemandFiller(name = "F1")
        // Seed an edge to register the filler.
        val seedSender = TestSender("Seed")
        carrier.setTransportTime(filler, seedSender, ConstantRV(1.0))
        val sender = TestSender("S1")
        carrier.attachDemandSenderToAllFillers(sender)
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        d.setFiller(filler)
        assertTrue(carrier.canShip(d))
    }

    @Test
    fun `immediate transport flag delivers immediately on unknown pair`() {
        val m = Model("TBNDC.Flag")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "TBNDC")
        carrier.immediateTransportFlag = true
        val sender = TestSender("S1")
        val filler = NoOpDemandFiller()

        val captured = mutableListOf<SupplyChainModel.Demand>()
        NetworkKickoff(sc, item, sender, filler, carrier, captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertEquals(0.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `unknown pair without immediate flag throws`() {
        val m = Model("TBNDC.Throw")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "TBNDC")
        val sender = TestSender("S1")
        val filler = NoOpDemandFiller()
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(1)
        assertThrows<NoCarrierOptionException> { carrier.transportDemand(d) }
    }

    private class NetworkKickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val sender: DemandSenderIfc,
        private val filler: DemandFillerIfc,
        private val carrier: TimeBasedNetworkDemandCarrier,
        private val captured: MutableList<SupplyChainModel.Demand>,
    ) : ModelElement(sc, "NetworkKickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            val d = sc.createDemand(item, 1)
            d.setDemandSender(sender)
            d.setFiller(filler)
            d.sent()
            d.receive(filler)
            d.process(filler)
            d.fill(1)
            carrier.transportDemand(d)
            captured += d
        }
    }
}
