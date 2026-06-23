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

class TimeBasedDemandCarrierTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    @Test
    fun `construction with no senders is empty`() {
        val m = Model("TBDC.Empty")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedDemandCarrier(sc, name = "TBDC")
        val sender = TestSender("S1")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        assertFalse(carrier.canShip(d))
        assertFalse(carrier.contains(sender))
        assertFalse(carrier.immediateTransportFlag)
    }

    @Test
    fun `canShip flips after configuring a transport time`() {
        val m = Model("TBDC.CanShip")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedDemandCarrier(sc, name = "TBDC")
        val sender = TestSender("S1")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        assertFalse(carrier.canShip(d))
        carrier.setTransportTime(sender, ConstantRV(2.0))
        assertTrue(carrier.canShip(d))
        assertTrue(carrier.contains(sender))
    }

    @Test
    fun `end-to-end ships and delivers after the configured delay`() {
        val m = Model("TBDC.E2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedDemandCarrier(sc, name = "TBDC")
        val sender = TestSender("S1")
        carrier.setTransportTime(sender, ConstantRV(2.0))

        val captured = mutableListOf<SupplyChainModel.Demand>()
        DemandKickoff(sc, item, sender, carrier, captured)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertEquals(2.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `immediate transport flag delivers at the ship time when sender is unknown`() {
        val m = Model("TBDC.Flag")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedDemandCarrier(sc, name = "TBDC")
        carrier.immediateTransportFlag = true
        val unknownSender = TestSender("UNK")

        val captured = mutableListOf<SupplyChainModel.Demand>()
        DemandKickoff(sc, item, unknownSender, carrier, captured)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertEquals(0.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `unknown sender without immediate flag throws`() {
        val m = Model("TBDC.Throw")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = TimeBasedDemandCarrier(sc, name = "TBDC")
        val sender = TestSender("S1")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(1)
        assertThrows<NoCarrierOptionException> { carrier.transportDemand(d) }
    }

    /**
     * Drives one demand through ship+deliver via the carrier. Lives in
     * a [ModelElement] so events scheduled by the carrier fire inside
     * the simulation run.
     */
    private class DemandKickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val sender: DemandSenderIfc,
        private val carrier: TimeBasedDemandCarrier,
        private val captured: MutableList<SupplyChainModel.Demand>,
    ) : ModelElement(sc, "DemandKickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            val d = sc.createDemand(item, 1)
            d.setDemandSender(sender)
            val filler = NoOpDemandFiller()
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
