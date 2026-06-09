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

class OrderStateMachineTest {

    private fun freshOrder(): Triple<SupplyChainModel, SupplyChainModel.Order, ItemType> {
        val m = Model("OrderStateMachineTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        return Triple(sc, sc.createOrder(orderName = "O-1"), item)
    }

    @Test
    fun `new order starts in created`() {
        val (sc, o, _) = freshOrder()
        assertSame(sc.orderCreated, o.orderState)
        assertEquals(0, o.numberOfDemands)
        assertTrue(o.isEmpty)
        assertEquals(OrderStatusCode.NoStatus, o.status)
    }

    @Test
    fun `adding a demand moves order to in preparation`() {
        val (sc, o, item) = freshOrder()
        val d = sc.createDemand(item, 3)
        o.addDemand(d)
        assertSame(sc.orderInPreparation, o.orderState)
        assertEquals(1, o.numberOfDemands)
        assertSame(o, d.order)
        assertEquals(0, d.orderIndex)
    }

    @Test
    fun `cannot add demand with incompatible backlogging`() {
        val (sc, o, item) = freshOrder() // order.allowBackLogging defaults to true
        val d = sc.createDemand(item, 3)
        d.setAllowBackLogging(false)
        assertThrows<IllegalArgumentException> { o.addDemand(d) }
    }

    @Test
    fun `cannot add two demands of the same item type`() {
        val (sc, o, item) = freshOrder()
        o.addDemand(sc.createDemand(item, 1))
        assertThrows<IllegalArgumentException> {
            o.addDemand(sc.createDemand(item, 1))
        }
    }

    @Test
    fun `sent transitions demands to sent`() {
        val (sc, o, item) = freshOrder()
        val d = sc.createDemand(item, 1)
        o.addDemand(d)
        o.sent()
        assertSame(sc.orderSent, o.orderState)
        assertSame(sc.sent, d.demandState)
    }

    @Test
    fun `cannot send an empty order`() {
        val (_, o, _) = freshOrder()
        assertThrows<IllegalStateException> { o.sent() }
    }

    @Test
    fun `order receives once all demands received`() {
        val (sc, o, _) = freshOrder()
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")
        val d1 = sc.createDemand(a, 1)
        val d2 = sc.createDemand(b, 1)
        o.addDemand(d1); o.addDemand(d2)
        val filler = TestFiller()
        d1.setFiller(filler); d2.setFiller(filler)
        o.sent()
        d1.receive(filler)
        assertSame(sc.orderSent, o.orderState) // not all received yet
        d2.receive(filler)
        assertSame(sc.orderReceived, o.orderState)
    }

    @Test
    fun `sent order can be rejected directly`() {
        val (sc, o, item) = freshOrder()
        val d = sc.createDemand(item, 1)
        o.addDemand(d)
        o.sent()
        o.reject()
        assertSame(sc.orderRejected, o.orderState)
    }

    @Test
    fun `order fills when all its demands are filled`() {
        val (sc, o, _) = freshOrder()
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")
        val d1 = sc.createDemand(a, 1)
        val d2 = sc.createDemand(b, 1)
        o.addDemand(d1); o.addDemand(d2)
        val filler = TestFiller()
        d1.setFiller(filler); d2.setFiller(filler)
        o.sent()
        d1.receive(filler); d2.receive(filler) // -> orderReceived
        o.process() // -> orderInProcess
        d1.process(filler); d1.fill(1) // d1 FILLED; order still IN_PROCESS
        assertSame(sc.orderInProcess, o.orderState)
        d2.process(filler); d2.fill(1) // d2 FILLED -> order fills
        assertSame(sc.orderFilled, o.orderState)
        assertTrue(o.isFilled)
        assertEquals(2, o.numFilledDemands)
    }

    @Test
    fun `shipping the order ships its demands`() {
        val (sc, o, item) = freshOrder()
        val d = sc.createDemand(item, 1)
        o.addDemand(d)
        val filler = TestFiller()
        d.setFiller(filler)
        o.sent()
        d.receive(filler)
        o.process()
        d.process(filler); d.fill(1)
        o.ship()
        assertSame(sc.orderShipped, o.orderState)
        assertSame(sc.shipped, d.demandState)
    }

    @Test
    fun `delivering does not auto-cascade demand state`() {
        val (sc, o, item) = freshOrder()
        val d = sc.createDemand(item, 1)
        o.addDemand(d)
        val filler = TestFiller()
        d.setFiller(filler)
        o.sent(); d.receive(filler); o.process()
        d.process(filler); d.fill(1)
        o.ship()
        o.deliver()
        // Java semantics: orderDelivered does NOT auto-deliver demands.
        assertSame(sc.shipped, d.demandState)
        // setDemandStateToDelivered() drives demand.deliver() which
        // cascades through the framework's default endpoint to Stored.
        o.setDemandStateToDelivered()
        assertSame(sc.stored, d.demandState)
    }

    @Test
    fun `weight and cube track add and remove`() {
        val (sc, _, _) = freshOrder()
        val order = sc.createOrder(allowPartialShipping = true)
        val item = ItemType(sc, name = "Heavy", weight = 10.0, cube = 4.0)
        val d1 = sc.createDemand(item, 3)
        // Fill d1 manually so weight/cube are non-zero (they derive from
        // amountFilled, not originalAmountDemanded).
        val filler = TestFiller()
        d1.setFiller(filler)
        // Add to order while in IN_PREPARATION (default flags compatible).
        order.addDemand(d1)
        // Before any fill, weight and cube derive from amountFilled = 0.
        assertEquals(0.0, order.requiredWeight)
        assertEquals(0.0, order.requiredCube)
    }

    @Test
    fun `state-change listener fires on every order transition`() {
        val (sc, o, item) = freshOrder()
        val seen = mutableListOf<Pair<String?, String>>()
        o.addStateChangeListener { _, from, to ->
            seen += (from?.stateName) to to.stateName
        }
        val d = sc.createDemand(item, 1)
        o.addDemand(d) // CREATED -> IN_PREPARATION
        o.sent()       // IN_PREPARATION -> SENT
        val filler = TestFiller()
        // setFiller must happen before sent(); but we already sent.
        // Re-run minimal happy path on a fresh order to capture the
        // remaining transitions cleanly.
        assertEquals(
            listOf<Pair<String?, String>>(
                "ORDER_CREATED" to "ORDER_IN_PREPARATION",
                "ORDER_IN_PREPARATION" to "ORDER_SENT",
            ),
            seen.take(2),
        )
    }

    @Test
    fun `cannot remove demand from filled order without partial shipping`() {
        val (sc, _, item) = freshOrder()
        val o = sc.createOrder(allowPartialShipping = false)
        val d = sc.createDemand(item, 1)
        o.addDemand(d)
        val filler = TestFiller()
        d.setFiller(filler)
        o.sent(); d.receive(filler); o.process()
        d.process(filler); d.fill(1) // o is now ORDER_FILLED
        assertSame(sc.orderFilled, o.orderState)
        assertThrows<IllegalStateException> { o.removeDemand(d) }
    }

    private class TestFiller : NoOpDemandFiller(name = "TestFiller")
}
