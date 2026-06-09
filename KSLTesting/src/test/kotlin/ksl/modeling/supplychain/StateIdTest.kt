package ksl.modeling.supplychain

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Regression guard for the Tier A1 sealed-class state IDs: keeps the
 * state vocabulary stable so listener bodies that use `when (to.stateId)`
 * keep working as expected. If a future contributor adds or renames a
 * state, this test will fail loudly.
 */
class StateIdTest {

    @Test
    fun `DemandStateId enumerates exactly the 12 known states`() {
        val names = DemandStateId::class.sealedSubclasses
            .map { it.objectInstance!!.displayName }
            .toSet()
        assertEquals(
            setOf(
                "IN_PREPARATION", "NEGOTIATING", "SENT", "RECEIVED",
                "IN_PROCESS", "BACKLOGGED", "REJECTED", "CANCELLED",
                "FILLED", "SHIPPED", "DELIVERED", "STORED",
            ),
            names,
        )
    }

    @Test
    fun `OrderStateId enumerates exactly the 12 known states`() {
        val names = OrderStateId::class.sealedSubclasses
            .map { it.objectInstance!!.displayName }
            .toSet()
        assertEquals(
            setOf(
                "ORDER_CREATED", "ORDER_IN_PREPARATION", "ORDER_SENT",
                "ORDER_NEGOTIATING", "ORDER_RECEIVED", "ORDER_IN_PROCESS",
                "ORDER_BACKLOGGED", "ORDER_REJECTED", "ORDER_CANCELLED",
                "ORDER_FILLED", "ORDER_SHIPPED", "ORDER_DELIVERED",
            ),
            names,
        )
    }

    @Test
    fun `DemandState stateName alias agrees with stateId displayName`() {
        val sc = SupplyChainModel(Model("StateId.Demand"))
        assertEquals("IN_PREPARATION", sc.inPreparation.stateName)
        assertSame(DemandStateId.InPreparation, sc.inPreparation.stateId)
        assertEquals("FILLED", sc.filled.stateName)
        assertSame(DemandStateId.Filled, sc.filled.stateId)
        assertEquals("DELIVERED", sc.delivered.stateName)
        assertSame(DemandStateId.Delivered, sc.delivered.stateId)
    }

    @Test
    fun `OrderState stateName alias agrees with stateId displayName`() {
        val sc = SupplyChainModel(Model("StateId.Order"))
        assertEquals("ORDER_CREATED", sc.orderCreated.stateName)
        assertSame(OrderStateId.Created, sc.orderCreated.stateId)
        assertEquals("ORDER_FILLED", sc.orderFilled.stateName)
        assertSame(OrderStateId.Filled, sc.orderFilled.stateId)
        assertEquals("ORDER_DELIVERED", sc.orderDelivered.stateName)
        assertSame(OrderStateId.Delivered, sc.orderDelivered.stateId)
    }
}
