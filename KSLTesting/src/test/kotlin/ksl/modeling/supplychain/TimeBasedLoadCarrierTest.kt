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

class TimeBasedLoadCarrierTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    @Test
    fun `assignLoadBuilder registers a per-destination builder and counter`() {
        val m = Model("TBLC.Assign")
        val sc = SupplyChainModel(m)
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC")
        val sender = TestSender("S1")
        val builder = carrier.assignLoadBuilder(sender, "B-S1")
        assertNotNull(builder)
        assertTrue(carrier.containsLoadBuilder(sender))
        assertSame(builder, carrier.getLoadBuilder(sender))
        assertNotNull(carrier.getShipmentCounter(sender))
    }

    @Test
    fun `cannot assign two load builders to the same destination`() {
        val sc = SupplyChainModel(Model("TBLC.Dup"))
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC")
        val sender = TestSender("S1")
        carrier.assignLoadBuilder(sender)
        assertThrows<IllegalArgumentException> {
            carrier.assignLoadBuilder(sender)
        }
    }

    @Test
    fun `transportDemand without a builder throws`() {
        val sc = SupplyChainModel(Model("TBLC.Throw"))
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC")
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(1)
        assertThrows<IllegalArgumentException> { carrier.transportDemand(d) }
    }

    @Test
    fun `reactToLoadBuildersFlag false keeps loads queued`() {
        val m = Model("TBLC.NoReact")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC")
        carrier.setTransportTime(sender, ConstantRV(1.0))
        val builder = carrier.assignLoadBuilder(sender, "B-S1")
        // Default reactToLoadBuildersFlag = false; ALWAYS forms loads
        // but the carrier does not ship them.
        assertFalse(carrier.reactToLoadBuildersFlag)

        val capturedLoadSize = intArrayOf(-1)
        LoadKickoff(sc, item, sender, carrier, fills = 2,
            afterAll = { capturedLoadSize[0] = builder.loadQueue.size })
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        // Loads stay queued through the replication; the Queue is
        // cleared in afterReplication, so we capture during init.
        assertEquals(2, capturedLoadSize[0])
        assertEquals(0.0, carrier.shipmentCounter.value)
    }

    @Test
    fun `reactToLoadBuildersFlag true ships loads after configured delay`() {
        val m = Model("TBLC.React")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC").apply {
            reactToLoadBuildersFlag = true
            setTransportTime(sender, ConstantRV(2.0))
        }
        val builder = carrier.assignLoadBuilder(sender, "B-S1")

        LoadKickoff(sc, item, sender, carrier, fills = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(0, builder.loadQueue.size)
        assertEquals(3.0, carrier.shipmentCounter.value)
    }

    @Test
    fun `immediate transport flag ships when destination has no transport time`() {
        val m = Model("TBLC.Flag")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC").apply {
            reactToLoadBuildersFlag = true
            immediateTransportFlag = true
        }
        // No setTransportTime call
        val builder = carrier.assignLoadBuilder(sender, "B-S1")

        LoadKickoff(sc, item, sender, carrier, fills = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        assertEquals(0, builder.loadQueue.size)
        assertEquals(1.0, carrier.shipmentCounter.value)
    }

    @Test
    fun `getLoadBuilder throws when destination is not assigned`() {
        val sc = SupplyChainModel(Model("TBLC.Lookup"))
        val carrier = TimeBasedLoadCarrier(sc, name = "TBLC")
        val sender = TestSender("S1")
        assertNull(carrier.getShipmentCounter(sender))
        assertThrows<IllegalArgumentException> { carrier.getLoadBuilder(sender) }
    }

    private class LoadKickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val sender: DemandSenderIfc,
        private val carrier: TimeBasedLoadCarrier,
        private val fills: Int,
        private val afterAll: () -> Unit = {},
    ) : ModelElement(sc, "LoadKickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            repeat(fills) {
                val d = sc.createDemand(item, 1)
                d.setDemandSender(sender)
                val filler = NoOpDemandFiller()
                d.setFiller(filler)
                d.sent()
                d.receive(filler)
                d.process(filler)
                d.fill(1)
                carrier.transportDemand(d)
            }
            afterAll()
        }
    }
}
