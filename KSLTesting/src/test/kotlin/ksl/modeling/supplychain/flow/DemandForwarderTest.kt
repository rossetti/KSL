package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandFillerAbstract
import ksl.modeling.supplychain.DemandFillerFinderIfc
import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandMessageIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.DemandStateId
import ksl.modeling.supplychain.DemandStatusCode
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.RoutesDemandsInternally
import ksl.modeling.supplychain.SupplyChainModel

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [DemandForwarder] (Helper C). Exercises the
 * full demand-forwarding flow: receive the original for processing,
 * propagate an equivalent request upstream with the lifecycle
 * observer attached, and on upstream delivery invoke the
 * onUpstreamDelivered callback with the original.
 */
class DemandForwarderTest {

    /**
     * Recording filler: stores every demand passed to receive,
     * optionally drives the clone through to delivery so the
     * caller can assert on the post-delivered callback.
     */
    private open class RecordingFiller(parent: ModelElement, name: String) :
        DemandFillerAbstract(parent, name = name), DemandSenderIfc {
        val received: MutableList<SupplyChainModel.Demand> = mutableListOf()
        var driveCloneToDelivered: Boolean = false

        override fun receive(demand: SupplyChainModel.Demand) {
            received += demand
            demand.receive(this)
            if (driveCloneToDelivered) {
                demand.process(this)
                demand.fill(demand.originalAmountDemanded)
                demand.ship()
                demand.deliver()
            }
        }
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
    }

    @Test
    fun `receive parks the original and forwards a clone with the right linkage`() {
        val sc = SupplyChainModel(Model("CF.Receive"))
        val upstream = RecordingFiller(sc, "Up")
        val sender = RecordingFiller(sc, "Sender")
        val forwarder = DemandForwarder(
            parent = sc,
            upstreamSupplier = { upstream },
            sender = sender,
            name = "Forwarder",
        )
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 4)
        d.sent()
        forwarder.receive(d)

        // Original parked in IN_PROCESS at the forwarder.
        assertEquals(DemandStateId.InProcess, d.demandState.stateId)
        // Exactly one clone reached the upstream.
        assertEquals(1, upstream.received.size)
        val clone = upstream.received[0]
        assertSame(d, clone.forwardedFrom)
        assertSame(sender, clone.demandSender)
        assertSame(upstream, clone.filler)
        assertEquals(item, clone.itemType)
        assertEquals(4, clone.originalAmountDemanded)
    }

    @Test
    fun `null upstream raises a clear error`() {
        val sc = SupplyChainModel(Model("CF.NullUpstream"))
        val sender = RecordingFiller(sc, "Sender")
        val forwarder = DemandForwarder(
            parent = sc,
            upstreamSupplier = { null },
            sender = sender,
            name = "Forwarder",
        )
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.sent()
        assertThrows<IllegalStateException> { forwarder.receive(d) }
    }

    @Test
    fun `RECEIVED listener drives upstream fillDemand by default`() {
        val sc = SupplyChainModel(Model("CF.FillOnReceive"))
        var upstreamFillCount = 0
        val upstream = object : RecordingFiller(sc, "Up") {
            override fun fillDemand(demand: SupplyChainModel.Demand) {
                upstreamFillCount += 1
            }
        }
        val sender = RecordingFiller(sc, "Sender")
        val forwarder = DemandForwarder(
            parent = sc,
            upstreamSupplier = { upstream },
            sender = sender,
            name = "Forwarder",
        )
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.sent()
        forwarder.receive(d)
        // upstream.receive() drove the clone to RECEIVED; the
        // lifecycle observer fired onReceived and called
        // upstream.fillDemand on the clone.
        assertEquals(1, upstreamFillCount)
    }

    @Test
    fun `shouldFillOnReceive opt-out skips upstream fillDemand`() {
        val sc = SupplyChainModel(Model("CF.SkipFill"))
        // Upstream advertises that it routes demands internally —
        // the policy should NOT call its fillDemand.
        var upstreamFillCount = 0
        val upstream = object : RecordingFiller(sc, "Up"), RoutesDemandsInternally {
            override fun fillDemand(demand: SupplyChainModel.Demand) {
                upstreamFillCount += 1
            }
        }
        val sender = RecordingFiller(sc, "Sender")
        val forwarder = DemandForwarder(
            parent = sc,
            upstreamSupplier = { upstream },
            sender = sender,
            shouldFillOnReceive = { filler -> filler !is RoutesDemandsInternally },
            name = "Forwarder",
        )
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.sent()
        forwarder.receive(d)
        assertEquals(0, upstreamFillCount)
    }

    @Test
    fun `onUpstreamDelivered fires with the recovered original`() {
        val sc = SupplyChainModel(Model("CF.OnDelivered"))
        val upstream = RecordingFiller(sc, "Up")
        upstream.driveCloneToDelivered = true
        val sender = RecordingFiller(sc, "Sender")
        var deliveredOriginal: SupplyChainModel.Demand? = null
        val forwarder = DemandForwarder(
            parent = sc,
            upstreamSupplier = { upstream },
            sender = sender,
            onUpstreamDelivered = { original -> deliveredOriginal = original },
            name = "Forwarder",
        )
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 2)
        d.sent()
        forwarder.receive(d)
        assertNotNull(deliveredOriginal)
        assertSame(d, deliveredOriginal)
    }

    @Test
    fun `null onUpstreamDelivered means the policy never invokes a callback`() {
        // Drives the clone through to DELIVERED upstream; with a
        // null onUpstreamDelivered the policy must not crash trying to
        // invoke a missing callback.
        val sc = SupplyChainModel(Model("CF.NullCallback"))
        val upstream = RecordingFiller(sc, "Up")
        upstream.driveCloneToDelivered = true
        val sender = RecordingFiller(sc, "Sender")
        val forwarder = DemandForwarder(
            parent = sc,
            upstreamSupplier = { upstream },
            sender = sender,
            onUpstreamDelivered = null,
            name = "Forwarder",
        )
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.sent()
        forwarder.receive(d)
        // Reaching here without exception is the assertion.
        // Quick smoke check that the original was indeed parked.
        assertEquals(DemandStateId.InProcess, d.demandState.stateId)
    }
}
