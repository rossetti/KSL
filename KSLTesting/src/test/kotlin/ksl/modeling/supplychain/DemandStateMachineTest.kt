package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemandStateMachineTest {

    private fun freshDemand(): Pair<SupplyChainModel, SupplyChainModel.Demand> {
        val m = Model("DemandStateMachineTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        return sc to sc.createDemand(item, amountDemanded = 5)
    }

    @Test
    fun `new demand starts in preparation`() {
        val (sc, d) = freshDemand()
        assertSame(sc.inPreparation, d.demandState)
        assertNull(d.previousDemandState)
        assertEquals(DemandStatusCode.NoStatus, d.status)
        assertEquals(5, d.originalAmountDemanded)
        assertEquals(5, d.remainingDemand)
        assertEquals(0, d.amountFilled)
        assertEquals(false, d.isFilled)
    }

    @Test
    fun `happy path prep to stored`() {
        val (sc, d) = freshDemand()
        val filler = TestFiller()
        d.setFiller(filler)
        d.sent()
        assertSame(sc.sent, d.demandState)
        d.receive(filler)
        assertSame(sc.received, d.demandState)
        d.process(filler)
        assertSame(sc.inProcess, d.demandState)
        d.fill(5)
        assertSame(sc.filled, d.demandState)
        assertTrue(d.isFilled)
        d.ship()
        assertSame(sc.shipped, d.demandState)
        // deliver() cascades to stored via the framework's default
        // PassThroughStorageEndpoint dispatch (universal Stored).
        d.deliver()
        assertSame(sc.stored, d.demandState)
    }

    @Test
    fun `partial fill keeps demand in process`() {
        val (sc, d) = freshDemand()
        val filler = TestFiller()
        d.setFiller(filler)
        d.sent(); d.receive(filler); d.process(filler)
        d.fill(2)
        assertSame(sc.inProcess, d.demandState)
        assertEquals(3, d.remainingDemand)
        d.fill(3)
        assertSame(sc.filled, d.demandState)
    }

    @Test
    fun `disallowed partial fill rejects mismatched amount`() {
        val (_, d) = freshDemand()
        val filler = TestFiller()
        d.setFiller(filler)
        d.setAllowPartialFilling(false)
        d.sent(); d.receive(filler); d.process(filler)
        assertThrows<IllegalArgumentException> { d.fill(3) }
    }

    @Test
    fun `illegal transitions throw IllegalStateException`() {
        val (_, d) = freshDemand()
        assertThrows<IllegalStateException> { d.deliver() }
        val filler = TestFiller()
        assertThrows<IllegalStateException> { d.receive(filler) }
    }

    @Test
    fun `state-change listener fires per Java gating rules`() {
        val (_, d) = freshDemand()
        val seen = mutableListOf<Pair<String?, String>>()
        d.addStateChangeListener { _, from, to ->
            seen += (from?.stateName) to to.stateName
        }
        val filler = TestFiller()
        d.setFiller(filler)
        d.sent()           // IN_PREPARATION -> SENT: silent (pre-active state)
        d.receive(filler)  // SENT -> RECEIVED: fires (no order attached)
        d.process(filler)  // RECEIVED -> IN_PROCESS: fires
        d.fill(5)          // IN_PROCESS -> FILLED: fires
        val expected: List<Pair<String?, String>> = listOf(
            "SENT" to "RECEIVED",
            "RECEIVED" to "IN_PROCESS",
            "IN_PROCESS" to "FILLED",
        )
        assertEquals(expected, seen)
    }

    @Test
    fun `negotiating allows return to preparation`() {
        val (sc, d) = freshDemand()
        d.negotiate()
        assertSame(sc.negotiating, d.demandState)
        d.prepare()
        assertSame(sc.inPreparation, d.demandState)
    }

    @Test
    fun `sent demand can be rejected and then re-prepared`() {
        val (sc, d) = freshDemand()
        val filler = TestFiller()
        d.setFiller(filler)
        d.sent()
        d.reject()
        assertSame(sc.rejected, d.demandState)
        d.prepare()
        assertSame(sc.inPreparation, d.demandState)
    }

    @Test
    fun `cancel disallowed by default`() {
        val (_, d) = freshDemand()
        val filler = TestFiller()
        d.setFiller(filler)
        d.sent(); d.receive(filler)
        assertThrows<IllegalStateException> { d.cancel() }
    }

    @Test
    fun `cancel allowed when flag set`() {
        val (sc, d) = freshDemand()
        val filler = TestFiller()
        d.setAllowCancelling(true)
        d.setFiller(filler)
        d.sent(); d.receive(filler); d.process(filler)
        d.cancel()
        assertSame(sc.cancelled, d.demandState)
    }

    private class TestFiller : NoOpDemandFiller(name = "TestFiller")
}
