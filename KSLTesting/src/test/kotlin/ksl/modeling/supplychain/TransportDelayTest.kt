package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransportDelayTest {

    @Test
    fun `construction with default zero delay does not throw`() {
        val m = Model("TD.Construct")
        val sc = SupplyChainModel(m)
        val td = TransportDelay(sc, name = "TD")
        assertNotNull(td.transitTimeResponse)
        assertNotNull(td.numInTransitResponse)
        assertNotNull(td.numShipmentsCounter)
    }

    @Test
    fun `end-to-end simulation ships then delivers after the delay`() {
        val m = Model("TD.E2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val td = TransportDelay(sc, ConstantRV(2.0), name = "TD")
        val captured = mutableListOf<SupplyChainModel.Demand>()

        Kickoff(sc, item, td, captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        // The kickoff at t=0 starts the delay; the demand is shipped
        // immediately and delivered at t=2.
        assertEquals(1, captured.size)
        val d = captured[0]
        assertSame(sc.stored, d.demandState)
        assertTrue(d.timeShipped <= d.timeDelivered)
        assertEquals(2.0, d.timeDelivered - d.timeShipped)
    }

    @Test
    fun `statistics accumulate over multiple shipments`() {
        val m = Model("TD.Stats")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val td = TransportDelay(sc, ConstantRV(0.5), name = "TD")

        MultiKickoff(sc, item, td, count = 4)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        // 4 shipments started at t=0, each takes 0.5; counter == 4.
        assertEquals(4.0, td.numShipmentsCounter.value)
    }

    /** Drives one demand through ship+deliver via the TransportDelay. */
    private class Kickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val td: TransportDelay,
        private val captured: MutableList<SupplyChainModel.Demand>,
    ) : ModelElement(sc, "Kickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            // Build a demand and walk it to FILLED so ship is legal.
            val d = sc.createDemand(item, 1)
            val filler = NoOpDemandFiller()
            d.setFiller(filler)
            d.sent()
            d.receive(filler)
            d.process(filler)
            d.fill(1) // demand now FILLED
            td.startDelay(d) // ship + schedule delivery
            captured += d
        }
    }

    /** Same but for N demands; only counts ship events. */
    private class MultiKickoff(
        sc: SupplyChainModel,
        private val item: ItemType,
        private val td: TransportDelay,
        private val count: Int,
    ) : ModelElement(sc, "MultiKickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            repeat(count) {
                val d = sc.createDemand(item, 1)
                val filler = NoOpDemandFiller()
                d.setFiller(filler)
                d.sent()
                d.receive(filler)
                d.process(filler)
                d.fill(1)
                td.startDelay(d)
            }
        }
    }
}
