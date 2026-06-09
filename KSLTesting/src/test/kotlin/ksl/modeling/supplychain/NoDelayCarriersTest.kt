package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NoDelayCarriersTest {

    @Test
    fun `NoDelayDemandCarrier transitions demand through ship+deliver`() {
        val m = Model("NDDC.Direct")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        // Drive demand to FILLED so ship+deliver are legal.
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(1)
        // Now use the carrier directly.
        NoDelayDemandCarrier.transportDemand(d)
        assertSame(sc.stored, d.demandState)
        assertTrue(NoDelayDemandCarrier.canShip(d))
    }

    @Test
    fun `NoDelayDemandCarrier as state listener fires on FILLED`() {
        val m = Model("NDDC.Listener")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.addStateChangeListener(NoDelayDemandCarrier)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(1) // FILLED listener fires, carrier ships + delivers.
        assertSame(sc.stored, d.demandState)
    }

    @Test
    fun `NoDelayOrderCarrier transitions order through ship+deliver`() {
        val m = Model("NDOC.Direct")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val o = sc.createOrder()
        val d = sc.createDemand(item, 1)
        o.addDemand(d)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        o.sent()
        d.receive(filler) // order batches to orderReceived
        o.process()       // -> orderInProcess
        d.process(filler)
        d.fill(1)         // order reaches orderFilled via the tracker
        NoDelayOrderCarrier.transportOrder(o)
        assertSame(sc.orderDelivered, o.orderState)
        assertTrue(NoDelayOrderCarrier.canShip(o))
    }
}
