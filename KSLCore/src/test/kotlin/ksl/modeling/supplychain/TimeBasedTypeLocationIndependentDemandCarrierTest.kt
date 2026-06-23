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

class TimeBasedTypeLocationIndependentDemandCarrierTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    @Test
    fun `construction with no senders is empty`() {
        val m = Model("TBTLIDC.Empty")
        val sc = SupplyChainModel(m)
        val carrier =
            TimeBasedTypeLocationIndependentDemandCarrier(sc, name = "TBT")
        val sender = TestSender("S1")
        assertFalse(carrier.contains(sender))
        assertFalse(carrier.immediateTransportFlag)
    }

    @Test
    fun `contains tracks sender and type separately`() {
        val m = Model("TBTLIDC.Contains")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")
        val carrier =
            TimeBasedTypeLocationIndependentDemandCarrier(sc, name = "TBT")
        val sender = TestSender("S1")
        carrier.setTransportTime(sender, a, ConstantRV(1.0))
        assertTrue(carrier.contains(sender))
        assertTrue(carrier.contains(sender, a))
        assertFalse(carrier.contains(sender, b))
    }

    @Test
    fun `canShip reflects sender, filler, and type configuration`() {
        val m = Model("TBTLIDC.CanShip")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val carrier =
            TimeBasedTypeLocationIndependentDemandCarrier(sc, name = "TBT")
        val sender = TestSender("S1")
        val d = sc.createDemand(a, 1)
        // No sender, no filler, no transport time -> false
        assertFalse(carrier.canShip(d))
        d.setDemandSender(sender)
        d.setFiller(NoOpDemandFiller())
        assertFalse(carrier.canShip(d))
        carrier.setTransportTime(sender, a, ConstantRV(1.0))
        assertTrue(carrier.canShip(d))
    }

    @Test
    fun `end-to-end ships and delivers after the configured delay`() {
        val m = Model("TBTLIDC.E2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier =
            TimeBasedTypeLocationIndependentDemandCarrier(sc, name = "TBT")
        val sender = TestSender("S1")
        carrier.setTransportTime(sender, item, ConstantRV(3.0))

        val captured = mutableListOf<SupplyChainModel.Demand>()
        DemandKickoff(sc, item, sender, carrier, captured)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertEquals(3.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `immediate transport flag delivers immediately on unknown pair`() {
        val m = Model("TBTLIDC.Flag")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier =
            TimeBasedTypeLocationIndependentDemandCarrier(sc, name = "TBT")
        carrier.immediateTransportFlag = true
        val sender = TestSender("S1")

        val captured = mutableListOf<SupplyChainModel.Demand>()
        DemandKickoff(sc, item, sender, carrier, captured)

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
        val m = Model("TBTLIDC.Throw")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier =
            TimeBasedTypeLocationIndependentDemandCarrier(sc, name = "TBT")
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

    private class DemandKickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val sender: DemandSenderIfc,
        private val carrier: TimeBasedTypeLocationIndependentDemandCarrier,
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
