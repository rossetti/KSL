package ksl.modeling.supplychain

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Tests for the Tier B3 `addOneShotListener` helpers on
 * [SupplyChainModel.Demand] and [SupplyChainModel.Order].
 */
class OneShotListenerTest {

    @Test
    fun `demand one-shot fires once when target state is first reached`() {
        val sc = SupplyChainModel(Model("OS.Demand.Fires"))
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        var fired = 0
        // Listener-firing is gated for pre-active states (IN_PREPARATION,
        // NEGOTIATING, SENT) — see Demand.transitionTo. Target REJECTED,
        // which is reached via sent() then reject() and DOES fire listeners.
        d.addOneShotListener(DemandStateId.Rejected) { fired++ }
        d.sent()
        d.reject()
        assertEquals(1, fired)
    }

    @Test
    fun `demand one-shot does not fire for non-target transitions`() {
        val sc = SupplyChainModel(Model("OS.Demand.NoFire"))
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        var fired = 0
        // Target REJECTED — but we'll only transition through SENT (silent).
        d.addOneShotListener(DemandStateId.Rejected) { fired++ }
        d.sent()
        assertEquals(0, fired)
    }

    @Test
    fun `demand one-shot auto-removes — re-entry of target state does not refire`() {
        val sc = SupplyChainModel(Model("OS.Demand.AutoRemove"))
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        var fired = 0
        d.addOneShotListener(DemandStateId.Rejected) { fired++ }

        // First REJECTED: should fire.
        d.sent()
        d.reject()
        assertEquals(1, fired)

        // Cycle back through prepare → sent → reject. Listener was
        // auto-removed after the first firing, so it should NOT fire
        // again.
        d.prepare()
        d.sent()
        d.reject()
        assertEquals(1, fired)
    }

    @Test
    fun `demand one-shot removes itself even when action throws`() {
        val sc = SupplyChainModel(Model("OS.Demand.Throws"))
        val item = ItemType(sc, name = "A")
        val d = sc.createDemand(item, 1)
        var fired = 0
        d.addOneShotListener(DemandStateId.Rejected) {
            fired++
            error("oops")
        }
        d.sent()
        assertThrows<IllegalStateException> { d.reject() }
        assertEquals(1, fired)

        // Listener should have been removed by the `finally` block.
        // Cycle back and re-trigger; firing-count stays at 1.
        d.prepare()
        d.sent()
        d.reject()
        assertEquals(1, fired)
    }

    @Test
    fun `order one-shot fires once when target state is first reached`() {
        val sc = SupplyChainModel(Model("OS.Order.Fires"))
        val item = ItemType(sc, name = "A")
        val o = sc.createOrder()
        val d = sc.createDemand(item, 1)
        o.addDemand(d)  // moves order to ORDER_IN_PREPARATION
        var fired = 0
        o.addOneShotListener(OrderStateId.Sent) { fired++ }
        o.sent()
        assertEquals(1, fired)
    }
}
