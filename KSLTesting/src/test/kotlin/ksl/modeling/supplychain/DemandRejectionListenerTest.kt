package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DemandRejectionListenerTest {

    /** Captures which dispatch fired (if any). */
    private class Spy : DemandRejectionListener() {
        var lastDispatch: String? = null
        override fun fillerUnavailable(demand: SupplyChainModel.Demand) {
            lastDispatch = "fillerUnavailable"
        }
        override fun itemTypeMismatch(demand: SupplyChainModel.Demand) {
            lastDispatch = "itemTypeMismatch"
        }
        override fun nonBackloggableToBackloggingReceiver(
            demand: SupplyChainModel.Demand,
        ) {
            lastDispatch = "nonBackloggableToBacklogging"
        }
        override fun backloggableToNonBackloggingReceiver(
            demand: SupplyChainModel.Demand,
        ) {
            lastDispatch = "backloggableToNonBacklogging"
        }
        override fun nonBackloggableToNonBackloggingReceiver(
            demand: SupplyChainModel.Demand,
        ) {
            lastDispatch = "nonBackloggableToNonBacklogging"
        }
        override fun orderRejected(demand: SupplyChainModel.Demand) {
            lastDispatch = "orderRejected"
        }
    }

    private fun freshDemand(): Pair<SupplyChainModel, SupplyChainModel.Demand> {
        val m = Model("DRL")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        return sc to sc.createDemand(item, 1)
    }

    @Test
    fun `fillerUnavailable dispatch fires`() {
        val (sc, d) = freshDemand()
        val spy = Spy()
        d.addStateChangeListener(spy)
        // Simulate the rejection by setting status and transitioning.
        d.setStatus(DemandStatusCode.FillerUnavailable)
        // Drive demand to RECEIVED then REJECTED.
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.reject()
        assertEquals(sc.rejected, d.demandState)
        assertEquals("fillerUnavailable", spy.lastDispatch)
    }

    @Test
    fun `itemTypeMismatch dispatch fires`() {
        val (_, d) = freshDemand()
        val spy = Spy()
        d.addStateChangeListener(spy)
        d.setStatus(DemandStatusCode.ItemTypeMismatch)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.reject()
        assertEquals("itemTypeMismatch", spy.lastDispatch)
    }

    @Test
    fun `orderRejected dispatch fires`() {
        val (_, d) = freshDemand()
        val spy = Spy()
        d.addStateChangeListener(spy)
        d.setStatus(DemandStatusCode.OrderRejected)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.reject()
        assertEquals("orderRejected", spy.lastDispatch)
    }

    @Test
    fun `non-rejected transitions are ignored`() {
        val (_, d) = freshDemand()
        val spy = Spy()
        d.addStateChangeListener(spy)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()      // pre-active states are silent anyway
        d.receive(filler)
        // No dispatch fired because to.stateName is RECEIVED, not REJECTED
        assertNull(spy.lastDispatch)
    }

    @Test
    fun `unmatched status code is silently ignored`() {
        val (_, d) = freshDemand()
        val spy = Spy()
        d.addStateChangeListener(spy)
        // Leave status at the default NoStatus — not in the dispatch table.
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.reject()
        assertNull(spy.lastDispatch)
    }

    @Test
    fun `default base-class throws on dispatch`() {
        val (_, d) = freshDemand()
        val listener = DemandRejectionListener()
        d.addStateChangeListener(listener)
        d.setStatus(DemandStatusCode.FillerUnavailable)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        try {
            d.reject()
            error("expected default fillerUnavailable to throw")
        } catch (e: IllegalStateException) {
            assertEquals(true, e.message?.contains("filler was unavailable"))
        }
    }
}
