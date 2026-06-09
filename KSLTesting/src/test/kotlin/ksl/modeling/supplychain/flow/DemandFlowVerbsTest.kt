package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandCarrierIfc
import ksl.modeling.supplychain.DemandFillerAbstract
import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandMessageIfc
import ksl.modeling.supplychain.DemandStateChangeListener
import ksl.modeling.supplychain.DemandStateId
import ksl.modeling.supplychain.DemandStatusCode
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the named state-flow verbs (Helper A):
 * `receiveForProcessing` and `fulfillAndDispatch`.
 */
class DemandFlowVerbsTest {

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

    private class RecordingCarrier : DemandCarrierIfc {
        val transported: MutableList<SupplyChainModel.Demand> = mutableListOf()
        override fun transportDemand(demand: SupplyChainModel.Demand) {
            transported += demand
        }
        override fun canShip(demand: SupplyChainModel.Demand) = true
    }

    @Test
    fun `receiveForProcessing drives SENT to IN_PROCESS`() {
        val sc = SupplyChainModel(Model("V.Park"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 2)
        d.sent()
        d.receiveForProcessing(filler)
        assertEquals(DemandStateId.InProcess, d.demandState.stateId)
    }

    @Test
    fun `receiveForProcessing fails fast when demand is not in SENT`() {
        val sc = SupplyChainModel(Model("V.ParkBad"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        // Demand is still IN_PREPARATION — receive is illegal.
        assertThrows<IllegalStateException> { d.receiveForProcessing(filler) }
    }

    @Test
    fun `fulfillAndDispatch with null carrier drives IN_PROCESS to STORED`() {
        val sc = SupplyChainModel(Model("V.CompleteDirect"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 3)
        d.sent()
        d.receiveForProcessing(filler)
        // fulfillAndDispatch drives ship+deliver; the framework's
        // PassThroughStorageEndpoint then dispatches Delivered → Stored.
        d.fulfillAndDispatch()
        assertEquals(DemandStateId.Stored, d.demandState.stateId)
        assertTrue(d.isFilled)
        assertEquals(3, d.amountFilled)
    }

    @Test
    fun `fulfillAndDispatch with carrier hands off without ship-deliver direct calls`() {
        val sc = SupplyChainModel(Model("V.CompleteCarrier"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        d.sent()
        d.receiveForProcessing(filler)
        val carrier = RecordingCarrier()
        d.fulfillAndDispatch(carrier = carrier)
        // Fill happened; the carrier is responsible for the rest.
        assertTrue(d.isFilled)
        assertEquals(1, carrier.transported.size)
        // We didn't drive ship/deliver — they're the carrier's job.
        // The demand is FILLED, not DELIVERED, until the carrier acts.
        assertEquals(DemandStateId.Filled, d.demandState.stateId)
    }

    @Test
    fun `verb sequence drives the same listener events as the underlying state machine`() {
        val sc = SupplyChainModel(Model("V.Listeners"))
        val filler = NoOpFiller(sc, "F")
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val transitions = mutableListOf<DemandStateId>()
        d.addStateChangeListener(
            DemandStateChangeListener { _, _, to -> transitions += to.stateId },
        )
        d.sent()
        d.receiveForProcessing(filler)
        d.fulfillAndDispatch()
        // The pre-active SENT transition is silent (per the protocol);
        // we expect RECEIVED, IN_PROCESS, FILLED, SHIPPED, DELIVERED,
        // then STORED via the framework's PassThroughStorageEndpoint
        // dispatch.  See `docs/supply-chain-framework-design.md` §3.5.
        assertEquals(
            listOf(
                DemandStateId.Received,
                DemandStateId.InProcess,
                DemandStateId.Filled,
                DemandStateId.Shipped,
                DemandStateId.Delivered,
                DemandStateId.Stored,
            ),
            transitions,
        )
    }
}
