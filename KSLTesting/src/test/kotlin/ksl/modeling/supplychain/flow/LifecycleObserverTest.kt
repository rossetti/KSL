package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandFillerAbstract
import ksl.modeling.supplychain.DemandMessageIfc
import ksl.modeling.supplychain.DemandStateId
import ksl.modeling.supplychain.DemandStatusCode
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for Helper B — the named lifecycle observer interfaces
 * for [SupplyChainModel.Demand] and [SupplyChainModel.Order].
 */
class LifecycleObserverTest {

    private class NoOpFiller(parent: ModelElement, name: String) :
        DemandFillerAbstract(parent, name = name) {
        override fun receive(demand: SupplyChainModel.Demand) {}
        override fun fillDemand(demand: SupplyChainModel.Demand) {}
        override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
        override fun canFillItemType(demand: SupplyChainModel.Demand) = true
        override fun canFillItemType(type: ItemType) = true
        override val itemTypes: Collection<ItemType> = emptyList()
        override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
            DemandStatusCode.NoStatus
        override fun willReject(demand: SupplyChainModel.Demand) = false
    }

    /**
     * Helper observer that records, in order, which lifecycle hooks
     * fired and on which demand. Implementing every hook lets the
     * test assert the full transition sequence.
     */
    private class RecordingDemandObserver : DemandLifecycleObserver {
        val events: MutableList<Pair<String, SupplyChainModel.Demand>> = mutableListOf()
        override fun onReceived(d: SupplyChainModel.Demand)   { events += "received"   to d }
        override fun onInProcess(d: SupplyChainModel.Demand)  { events += "inProcess"  to d }
        override fun onFilled(d: SupplyChainModel.Demand)     { events += "filled"     to d }
        override fun onShipped(d: SupplyChainModel.Demand)    { events += "shipped"    to d }
        override fun onDelivered(d: SupplyChainModel.Demand)  { events += "delivered"  to d }
        override fun onRejected(d: SupplyChainModel.Demand)   { events += "rejected"   to d }
        override fun onCancelled(d: SupplyChainModel.Demand)  { events += "cancelled"  to d }
        override fun onBackLogged(d: SupplyChainModel.Demand) { events += "backLogged" to d }
    }

    @Test
    fun `observer fires onDelivered for the full lifecycle path`() {
        val sc = SupplyChainModel(Model("LO.Full"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val obs = RecordingDemandObserver()
        d.observe(obs)
        d.sent()
        d.receiveForProcessing(filler)
        d.fulfillAndDispatch()
        assertEquals(
            listOf("received", "inProcess", "filled", "shipped", "delivered"),
            obs.events.map { it.first },
        )
        // Every event passed the same demand reference.
        for ((_, demand) in obs.events) assertSame(d, demand)
    }

    @Test
    fun `observer fires onRejected when demand transitions to REJECTED`() {
        val sc = SupplyChainModel(Model("LO.Reject"))
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val obs = RecordingDemandObserver()
        d.observe(obs)
        d.sent()
        d.reject()
        assertEquals(listOf("rejected"), obs.events.map { it.first })
    }

    @Test
    fun `partial observer implementation only sees the hooks it overrides`() {
        val sc = SupplyChainModel(Model("LO.Partial"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        // Observer cares only about delivery; everything else is no-op.
        var deliveredCount = 0
        d.observe(object : DemandLifecycleObserver {
            override fun onDelivered(d: SupplyChainModel.Demand) {
                deliveredCount += 1
            }
        })
        d.sent()
        d.receiveForProcessing(filler)
        d.fulfillAndDispatch()
        assertEquals(1, deliveredCount)
    }

    @Test
    fun `removeStateChangeListener on the returned listener detaches the observer`() {
        val sc = SupplyChainModel(Model("LO.Remove"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val obs = RecordingDemandObserver()
        val listener = d.observe(obs)
        d.sent()
        d.receiveForProcessing(filler)   // received, inProcess
        d.removeStateChangeListener(listener)
        d.fulfillAndDispatch()       // filled, shipped, delivered — not seen
        assertEquals(listOf("received", "inProcess"), obs.events.map { it.first })
    }

    @Test
    fun `pre-active state transitions do not fire any hook`() {
        // sent → reject is allowed; SENT itself is silent so the
        // observer must not see an onSent equivalent (none exists).
        val sc = SupplyChainModel(Model("LO.Silent"))
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val obs = RecordingDemandObserver()
        d.observe(obs)
        d.sent()   // SENT is silent
        assertEquals(emptyList<String>(), obs.events.map { it.first })
    }
}
