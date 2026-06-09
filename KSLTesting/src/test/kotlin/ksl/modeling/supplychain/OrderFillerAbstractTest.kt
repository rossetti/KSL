package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrderFillerAbstractTest {

    @Test
    fun `unavailable filler rejects the order with FillerUnavailable`() {
        val (sc, of, item) = setup()
        of.flipAvailability(false)
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(item, 1))
        o.sent()
        of.receive(o)
        assertSame(sc.orderRejected, o.orderState)
        assertEquals(OrderStatusCode.FillerUnavailable, o.status)
    }

    @Test
    fun `successful receive completes the batch and order receives`() {
        val (sc, of, item) = setup()
        of.demandFiller.willRejectDecision = false
        of.demandFiller.requestStatusDecision = DemandStatusCode.ImmediateFill
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(item, 1))
        o.sent()
        of.receive(o)
        assertSame(sc.orderReceived, o.orderState)
        assertEquals(OrderStatusCode.ImmediateFill, o.status)
    }

    @Test
    fun `order with a rejecting demand is rejected`() {
        val (sc, of, item) = setup()
        of.demandFiller.willRejectDecision = true
        of.demandFiller.requestStatusDecision = DemandStatusCode.FillerUnavailable
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(item, 1))
        o.sent()
        of.receive(o)
        assertSame(sc.orderRejected, o.orderState)
        assertEquals(OrderStatusCode.DemandRejected, o.status)
    }

    @Test
    fun `missing demand filler throws NoDemandFillerFoundException`() {
        val (sc, of, item) = setup()
        of.findResult = null
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(item, 1))
        o.sent()
        assertThrows<NoDemandFillerFoundException> { of.receive(o) }
    }

    @Test
    fun `WillBeBacklogged propagates to order status`() {
        val (sc, of, item) = setup()
        of.demandFiller.willRejectDecision = false
        of.demandFiller.requestStatusDecision = DemandStatusCode.WillBeBacklogged
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(item, 1))
        o.sent()
        of.receive(o)
        assertEquals(OrderStatusCode.WillBeBacklogged, o.status)
        assertTrue(o.isState(sc.orderReceived))
    }

    @Test
    fun `canFillItemTypes is true only if all demands fillable`() {
        val (sc, of, _) = setup()
        of.canFillDecision = true
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(ItemType(sc, name = "A"), 1))
        o.addDemand(sc.createDemand(ItemType(sc, name = "B"), 1))
        assertTrue(of.canFillItemTypes(o))
        of.canFillDecision = false
        assertEquals(false, of.canFillItemTypes(o))
    }

    private fun setup(): Triple<SupplyChainModel, TestOrderFiller, ItemType> {
        val m = Model("OrderFillerAbstractTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val of = TestOrderFiller(sc)
        return Triple(sc, of, item)
    }

    /**
     * Concrete OrderFillerAbstract subclass wired with a controllable
     * DemandFillerFinder and a controllable TestDemandFiller.
     */
    private class TestOrderFiller(parent: SupplyChainModel)
        : OrderFillerAbstract(parent) {

        val demandFiller = TestDemandFiller()
        var findResult: DemandFillerIfc? = demandFiller
        var canFillDecision: Boolean = true

        init {
            demandFillerFinder = DemandFillerFinderIfc { findResult }
        }

        fun flipAvailability(flag: Boolean) = setAvailability(flag)

        override fun fill(order: SupplyChainModel.Order) {}
        override fun canFillItemType(demand: SupplyChainModel.Demand) = canFillDecision
        override fun negotiate(order: SupplyChainModel.Order): OrderMessageIfc? = null
    }

    /**
     * Test demand-filler. `receive()` mutates the demand state
     * synchronously so the order's batching counters advance.
     */
    private class TestDemandFiller : DemandFillerIfc {
        override val name = "TestDemandFiller"
        override val id = 0
        override var label: String? = null
        override val isAvailable = true
        var willRejectDecision: Boolean = false
        var requestStatusDecision: DemandStatusCode = DemandStatusCode.ImmediateFill

        override fun receive(demand: SupplyChainModel.Demand) {
            // Demand state machine requires going through `received` before
            // `rejected` (Sent has no reject override). For the rejecting
            // path we receive first, then reject from received.
            demand.receive(this)
            if (willRejectDecision) demand.reject()
        }
        override fun fillDemand(demand: SupplyChainModel.Demand) {}
        override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
        override fun canFillItemType(demand: SupplyChainModel.Demand) = true
        override fun canFillItemType(type: ItemType) = true
        override val itemTypes: Collection<ItemType> = emptyList()
        override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
            requestStatusDecision
        override fun willReject(demand: SupplyChainModel.Demand) = willRejectDecision
        override var demandCarrier: DemandCarrierIfc? = null
        override var demandPreparer: DemandPreparerIfc? = null
    }
}
