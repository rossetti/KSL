package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandFillerAbstract
import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandFillerFinderIfc
import ksl.modeling.supplychain.DemandMessageIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.DemandStateChangeListener
import ksl.modeling.supplychain.DemandStateId
import ksl.modeling.supplychain.DemandStatusCode
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.NetworkNodeIfc

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the Phase 1.C/1.D state-machine extension:
 *  - new `Stored` terminal state
 *  - Delivered → Stored via the framework's default
 *    `PassThroughStorageEndpoint`
 *  - Delivered → Shipped re-ship transition (multi-hop pass-through)
 *  - `onStored` lifecycle observer hook
 *  - `NetworkNodeIfc.deliveryEndpoint` slot routing
 */
class StoredStateTest {

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

    private fun walkToDelivered(d: SupplyChainModel.Demand, filler: NoOpFiller) {
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(d.originalAmountDemanded)
        d.ship()
        d.deliver()
    }

    @Test
    fun `Stored is the terminal state via the default PassThroughStorageEndpoint`() {
        val sc = SupplyChainModel(Model("SS.Default"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        walkToDelivered(d, filler)
        // With no sender (so no NetworkNodeIfc lookup), the framework
        // falls back to PassThroughStorageEndpoint which calls
        // demand.store() immediately on Delivered.
        assertSame(sc.stored, d.demandState)
        assertEquals(DemandStateId.Stored, d.demandState.stateId)
    }

    @Test
    fun `timeStored records when the demand was stored`() {
        val sc = SupplyChainModel(Model("SS.Time"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        walkToDelivered(d, filler)
        // Same simulation time for delivered and stored under the
        // default pass-through endpoint.
        assertTrue(!d.timeStored.isNaN())
        assertEquals(d.timeDelivered, d.timeStored)
    }

    @Test
    fun `onStored lifecycle hook fires once on terminal transition`() {
        val sc = SupplyChainModel(Model("SS.Hook"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        var storedFired = 0
        var deliveredFired = 0
        d.observe(object : DemandLifecycleObserver {
            override fun onDelivered(d: SupplyChainModel.Demand) {
                deliveredFired += 1
            }
            override fun onStored(d: SupplyChainModel.Demand) {
                storedFired += 1
            }
        })
        walkToDelivered(d, filler)
        assertEquals(1, deliveredFired)
        assertEquals(1, storedFired)
    }

    @Test
    fun `listeners see Delivered before Stored chronologically`() {
        // The framework dispatches the endpoint AFTER all user
        // Delivered listeners have fired, preserving chronological
        // intuition (see SupplyChainModel.Demand.transitionTo).
        val sc = SupplyChainModel(Model("SS.Order"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val log = mutableListOf<DemandStateId>()
        d.addStateChangeListener(
            DemandStateChangeListener { _, _, to -> log += to.stateId },
        )
        walkToDelivered(d, filler)
        // SENT is silent in the underlying machine, so the log
        // starts at RECEIVED.
        val expectedTail = listOf(DemandStateId.Delivered, DemandStateId.Stored)
        assertEquals(expectedTail, log.takeLast(2))
    }

    @Test
    fun `Delivered to Shipped re-ship transition is legal for multi-hop`() {
        // Direct test of the new transition (no carrier or routing
        // endpoint configured — we just verify the state machine
        // accepts deliver() then ship() then deliver() then store().
        val sc = SupplyChainModel(Model("SS.ReShip"))
        val filler = NoOpFiller(sc, "F")
        // Use a NetworkNodeIfc sender that doesn't auto-route at
        // Delivered, so we can call ship() manually for the second
        // leg.  Easiest: a sender whose deliveryEndpoint is a no-op.
        val noopEndpoint = object : DeliveryEndpointIfc {
            override fun onDelivered(demand: SupplyChainModel.Demand) {
                // intentionally inert — test will drive transitions
            }
        }
        val sender = object : DemandFillerAbstract(sc, name = "Sender"),
            DemandSenderIfc, NetworkNodeIfc {
            override fun receive(demand: SupplyChainModel.Demand) {}
            override fun fillDemand(demand: SupplyChainModel.Demand) {}
            override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
            override fun canFillItemType(demand: SupplyChainModel.Demand) = true
            override fun canFillItemType(type: ItemType) = true
            override val itemTypes: Collection<ItemType> = emptyList()
            override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
                DemandStatusCode.NoStatus
            override fun willReject(demand: SupplyChainModel.Demand) = false
            override fun mightRequest(type: ItemType): Boolean = false
            override var demandFillerFinder: DemandFillerFinderIfc? = null
            override var demandFiller: DemandFillerIfc? = null
            override var level: Int = 0
            override var deliveryEndpoint: DeliveryEndpointIfc = noopEndpoint
        }
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(1)
        d.ship()
        d.deliver()
        // Delivered, endpoint is no-op so we stay at Delivered.
        assertSame(sc.delivered, d.demandState)
        // Re-ship for the next leg (the user's RoutingEndpoint
        // would do this).
        d.ship()
        assertSame(sc.shipped, d.demandState)
        d.deliver()
        // Endpoint is still no-op, so we end at Delivered until
        // store() is explicitly called.
        assertSame(sc.delivered, d.demandState)
        d.store()
        assertSame(sc.stored, d.demandState)
    }

    @Test
    fun `custom endpoint on the sender drives the terminal action`() {
        val sc = SupplyChainModel(Model("SS.CustomEP"))
        val filler = NoOpFiller(sc, "F")
        var endpointFires = 0
        val endpoint = object : DeliveryEndpointIfc {
            override fun onDelivered(demand: SupplyChainModel.Demand) {
                endpointFires += 1
                demand.store()
            }
        }
        val sender = object : DemandFillerAbstract(sc, name = "Sender"),
            DemandSenderIfc, NetworkNodeIfc {
            override fun receive(demand: SupplyChainModel.Demand) {}
            override fun fillDemand(demand: SupplyChainModel.Demand) {}
            override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
            override fun canFillItemType(demand: SupplyChainModel.Demand) = true
            override fun canFillItemType(type: ItemType) = true
            override val itemTypes: Collection<ItemType> = emptyList()
            override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
                DemandStatusCode.NoStatus
            override fun willReject(demand: SupplyChainModel.Demand) = false
            override fun mightRequest(type: ItemType): Boolean = false
            override var demandFillerFinder: DemandFillerFinderIfc? = null
            override var demandFiller: DemandFillerIfc? = null
            override var level: Int = 0
            override var deliveryEndpoint: DeliveryEndpointIfc = endpoint
        }
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        walkToDelivered(d, filler)
        assertEquals(1, endpointFires)
        assertSame(sc.stored, d.demandState)
    }
}
