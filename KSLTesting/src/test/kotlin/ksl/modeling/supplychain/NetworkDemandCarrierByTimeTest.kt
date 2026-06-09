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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkDemandCarrierByTimeTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    @Test
    fun `getTransportDelay is null until configured`() {
        val sc = SupplyChainModel(Model("NDCBT.Lookup"))
        val carrier = NetworkDemandCarrierByTime(sc, name = "NDCBT")
        val sender = TestSender("S1")
        val filler = NoOpDemandFiller()
        assertNull(carrier.getTransportDelay(filler, sender))
        carrier.setTransportTime(filler, sender, ConstantRV(1.5))
        assertNotNull(carrier.getTransportDelay(filler, sender))
    }

    @Test
    fun `setTransportTime overloads accept double and zero-delay defaults`() {
        val sc = SupplyChainModel(Model("NDCBT.Overloads"))
        val carrier = NetworkDemandCarrierByTime(sc, name = "NDCBT")
        val filler = NoOpDemandFiller(name = "F1")
        val s1 = TestSender("S1")
        val s2 = TestSender("S2")
        val s3 = TestSender("S3")
        carrier.setTransportTime(filler, s1)            // zero-delay
        carrier.setTransportTime(filler, s2, 0.5)       // double
        carrier.setTransportTime(filler, s3, ConstantRV(1.0))
        assertNotNull(carrier.getTransportDelay(filler, s1))
        assertNotNull(carrier.getTransportDelay(filler, s2))
        assertNotNull(carrier.getTransportDelay(filler, s3))
    }

    @Test
    fun `end-to-end ships and delivers via configured transport delay`() {
        val m = Model("NDCBT.E2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = NetworkDemandCarrierByTime(sc, name = "NDCBT")
        val sender = TestSender("S1")
        val filler = NoOpDemandFiller(name = "F1")
        carrier.setTransportTime(filler, sender, ConstantRV(2.0))

        val captured = mutableListOf<SupplyChainModel.Demand>()
        NetKickoff(sc, item, sender, filler, carrier, captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertEquals(2.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `default DemandGenerator immediate flag permits ship without an edge`() {
        val m = Model("NDCBT.DG")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = NetworkDemandCarrierByTime(sc, name = "NDCBT")
        assertTrue(carrier.demandGeneratorImmediateTransportFlag)
        // A real DemandGenerator instance triggers the is-DemandGenerator path
        val gen = DemandGenerator(
            supplyChainModel = sc,
            itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        val filler = NoOpDemandFiller()
        val d = sc.createDemand(item, 1)
        d.setDemandSender(gen)
        d.setFiller(filler)
        assertTrue(carrier.canShip(d))
        carrier.demandGeneratorImmediateTransportFlag = false
        assertFalse(carrier.canShip(d))
    }

    @Test
    fun `default LeadTimeDemandFiller immediate flag permits ship without an edge`() {
        val m = Model("NDCBT.LT")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = NetworkDemandCarrierByTime(sc, name = "NDCBT")
        assertTrue(carrier.externalSupplierImmediateTransportFlag)
        val supplier = LeadTimeDemandFiller(sc, name = "LT").apply {
            addLeadTime(item, ConstantRV(0.5))
        }
        val customer = TestSender("S1")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(customer)
        d.setFiller(supplier)
        assertTrue(carrier.canShip(d))
        carrier.externalSupplierImmediateTransportFlag = false
        assertFalse(carrier.canShip(d))
    }

    @Test
    fun `transportDemand throws when canShip is false`() {
        val m = Model("NDCBT.Throw")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val carrier = NetworkDemandCarrierByTime(sc, name = "NDCBT")
        // Customer is a plain test sender, supplier is plain test filler,
        // and both immediate flags wouldn't help: only the
        // (DemandGenerator or LeadTimeDemandFiller) instanceof paths
        // permit immediate. Use neither.
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

    private class NetKickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val sender: DemandSenderIfc,
        private val filler: DemandFillerIfc,
        private val carrier: NetworkDemandCarrierByTime,
        private val captured: MutableList<SupplyChainModel.Demand>,
    ) : ModelElement(sc, "NetKickoff") {
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
