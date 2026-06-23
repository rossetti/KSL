package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LeadTimeOrderFillerTest {

    @Test
    fun `unavailable filler rejects the order`() {
        val m = Model("LTOF.Unavailable")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val filler = TestLTOF(sc, initialAvailability = false)
        filler.addLeadTime(item, ConstantRV(1.0))
        val o = sc.createOrder()
        o.addDemand(sc.createDemand(item, 1))
        o.sent()
        filler.receive(o)
        assertSame(sc.orderRejected, o.orderState)
        assertEquals(OrderStatusCode.FillerUnavailable, o.status)
    }

    @Test
    fun `end-to-end simulation fills, ships, delivers a one-demand order`() {
        val m = Model("LTOF.OneDemand")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val filler = LeadTimeOrderFiller(sc)
        filler.addLeadTime(item, ConstantRV(2.0))
        val captured = mutableListOf<SupplyChainModel.Order>()
        OrderKickoff(sc, filler, listOf(item), captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        val o = captured.last()
        assertSame(sc.orderDelivered, o.orderState)
        assertTrue(o.isFilled)
        assertEquals(1, o.numFilledDemands)
        // The lone demand on the order cascades through ship via the
        // order's shipped-listener but does NOT cascade to delivered.
        val d = o.getDemand(0)
        assertSame(sc.shipped, d.demandState)
    }

    @Test
    fun `end-to-end simulation fills multi-demand order`() {
        val m = Model("LTOF.MultiDemand")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")
        val filler = LeadTimeOrderFiller(sc)
        filler.addLeadTime(a, ConstantRV(2.0))
        filler.addLeadTime(b, ConstantRV(5.0))
        val captured = mutableListOf<SupplyChainModel.Order>()
        OrderKickoff(sc, filler, listOf(a, b), captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        val o = captured.last()
        assertSame(sc.orderDelivered, o.orderState)
        assertEquals(2, o.numFilledDemands)
        // Both demands transitioned through ship via the order's cascade.
        assertSame(sc.shipped, o.getDemand(0).demandState)
        assertSame(sc.shipped, o.getDemand(1).demandState)
    }

    private class TestLTOF(
        parent: SupplyChainModel,
        initialAvailability: Boolean,
    ) : LeadTimeOrderFiller(parent, initialAvailability, name = "TestLTOF")

    /**
     * Drives a fresh order (with one demand per item type) through the
     * filler at each replication start. The order's demands are pre-
     * configured with the right backlogging compatibility.
     */
    private class OrderKickoff(
        sc: SupplyChainModel,
        private val filler: LeadTimeOrderFiller,
        private val items: List<ItemType>,
        private val captured: MutableList<SupplyChainModel.Order>,
    ) : ModelElement(sc, "OrderKickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            val o = sc.createOrder()
            for (it in items) o.addDemand(sc.createDemand(it, 1))
            o.sent()
            filler.receive(o)
            // Successful receive leaves the order in orderReceived; the
            // filler.fill(o) call drives it through process and fill.
            filler.fill(o)
            captured.add(o)
        }
    }
}
