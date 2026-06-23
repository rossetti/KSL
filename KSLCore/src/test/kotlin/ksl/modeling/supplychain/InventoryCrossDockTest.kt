package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit-level tests for [InventoryCrossDock]: defaults, the receive
 * → clone → upstream flow, the clone-delivered listener (fills the
 * original and ships it), and the statistics.
 */
class InventoryCrossDockTest {

    /**
     * Minimal recording filler: captures every demand passed to
     * [receive], lets the test fire arbitrary downstream transitions
     * to drive the cross-dock's listener path.
     */
    private class RecordingFiller(parent: ModelElement, name: String) :
        DemandFillerAbstract(parent, name = name) {
        val received: MutableList<SupplyChainModel.Demand> = mutableListOf()
        override fun receive(demand: SupplyChainModel.Demand) {
            // Walk the demand into IN_PROCESS so the test can call
            // fill() on it.
            demand.receive(this)
            demand.process(this)
            received += demand
        }
        override fun fillDemand(demand: SupplyChainModel.Demand) {}
        override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
        override fun canFillItemType(demand: SupplyChainModel.Demand) = true
        override fun canFillItemType(type: ItemType) = true
        override val itemTypes: Collection<ItemType> = emptyList()
        override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
            DemandStatusCode.NoStatus
        override fun willReject(demand: SupplyChainModel.Demand) = false
    }

    private fun simpleSetup(modelName: String): Triple<SupplyChainModel, InventoryCrossDock, RecordingFiller> {
        val sc = SupplyChainModel(Model(modelName))
        val cd = InventoryCrossDock(sc, name = "CD")
        val upstream = RecordingFiller(sc, "Upstream")
        cd.demandFiller = upstream
        return Triple(sc, cd, upstream)
    }

    @Test
    fun `defaults expose null upstream, null carrier, level zero`() {
        val sc = SupplyChainModel(Model("CD.Defaults"))
        val cd = InventoryCrossDock(sc, name = "CD")
        assertEquals(0, cd.level)
        assertSame(null, cd.demandFiller)
        assertSame(null, cd.demandCarrier)
        assertTrue(cd.isAvailable)
        assertTrue(cd.itemTypes.isEmpty())
    }

    @Test
    fun `receive without an upstream filler fails fast`() {
        val sc = SupplyChainModel(Model("CD.NoUpstream"))
        val cd = InventoryCrossDock(sc, name = "CD")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.sent()
        assertThrows<IllegalStateException> { cd.receive(d) }
    }

    @Test
    fun `receive clones and forwards - clone carries the typed forwardedFrom`() {
        val (sc, cd, upstream) = simpleSetup("CD.Clone")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 3)
        d.sent()
        cd.receive(d)
        assertEquals(1, upstream.received.size)
        val clone = upstream.received[0]
        // The clone is a distinct demand carrying the original.
        assertSame(d, clone.forwardedFrom)
        assertSame(cd, clone.demandSender)
        assertSame(upstream, clone.filler)
        assertEquals(item, clone.itemType)
        assertEquals(3, clone.originalAmountDemanded)
    }

    @Test
    fun `clone delivery fills the original and direct-ships when no carrier`() {
        val (sc, cd, upstream) = simpleSetup("CD.DeliverNoCarrier")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 2)
        d.sent()
        cd.receive(d)
        val clone = upstream.received[0]
        // Upstream walks the clone through fill → ship → deliver.
        clone.fill(clone.originalAmountDemanded)
        clone.ship()
        clone.deliver()
        // The cross-dock's listener filled and ship/delivered the original.
        assertTrue(d.isFilled)
        assertEquals(DemandStateId.Stored, d.demandState.stateId)
    }

    @Test
    fun `numberOfDemandsCrossDocked and waitTime stats observed`() {
        val (sc, cd, upstream) = simpleSetup("CD.Stats")
        val item = ItemType(sc, name = "A")
        // Two trips through the cross-dock — same simulation time (no
        // event loop here), so wait times are zero but the counter
        // increments twice.
        for (i in 1..2) {
            val d = sc.createDemand(item, 1)
            d.sent()
            cd.receive(d)
            val clone = upstream.received[i - 1]
            clone.fill(clone.originalAmountDemanded)
            clone.ship()
            clone.deliver()
        }
        assertEquals(2.0, cd.numberOfDemandsCrossDockedResponse.value)
        // Two observations recorded; both with value 0.0 (same-time delivery).
        assertEquals(2.0, cd.crossDockWaitTimeResponse.withinReplicationStatistic.count)
    }

    @Test
    fun `unavailable cross-dock rejects incoming demand`() {
        val sc = SupplyChainModel(Model("CD.Unavailable"))
        val cd = object : InventoryCrossDock(sc, initialAvailability = false, name = "CD") {}
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        // sent → reject is allowed; receive on an unavailable filler
        // sets the status and rejects.
        d.sent()
        cd.receive(d)
        assertEquals(DemandStatusCode.FillerUnavailable, d.status)
        assertEquals(DemandStateId.Rejected, d.demandState.stateId)
    }
}
