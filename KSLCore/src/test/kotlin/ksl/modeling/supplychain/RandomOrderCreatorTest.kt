package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RandomOrderCreatorTest {

    @Test
    fun `createsType returns true only for registered types`() {
        val (sc, c, a) = setup()
        val b = ItemType(sc, name = "B")
        c.addItemTypeDistribution(a)
        assertTrue(c.createsType(a))
        assertFalse(c.createsType(b))
    }

    @Test
    fun `cannot register the same item type twice`() {
        val (_, c, a) = setup()
        c.addItemTypeDistribution(a)
        assertThrows<IllegalArgumentException> {
            c.addItemTypeDistribution(a)
        }
    }

    @Test
    fun `createOrder with always-include distributions returns order with all demands`() {
        val (sc, c, a) = setup()
        val b = ItemType(sc, name = "B")
        c.addItemTypeDistribution(a, amountDistribution = ConstantRV(4.0))
        c.addItemTypeDistribution(b, amountDistribution = ConstantRV(7.0))
        val o = c.createOrder()
        assertNotNull(o)
        assertEquals(2, o.numberOfDemands)
        assertEquals(4, o.getDemand(0).originalAmountDemanded)
        assertEquals(7, o.getDemand(1).originalAmountDemanded)
    }

    @Test
    fun `createOrder with never-include returns null`() {
        val (_, c, a) = setup()
        c.addItemTypeDistribution(a, includeDistribution = ConstantRV.ZERO,
            amountDistribution = ConstantRV(3.0))
        assertNull(c.createOrder())
    }

    @Test
    fun `backlogging flag propagates to created order and its demands`() {
        val (_, c, a) = setup()
        c.permitBackLogging = false
        c.addItemTypeDistribution(a, amountDistribution = ConstantRV(1.0))
        val o = c.createOrder()!!
        assertFalse(o.allowBackLogging)
        assertFalse(o.getDemand(0).allowBackLogging)
    }

    private data class Setup(
        val sc: SupplyChainModel,
        val creator: RandomOrderCreator,
        val itemA: ItemType,
    )

    private fun setup(): Setup {
        val m = Model("RandomOrderCreatorTest")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val creator = RandomOrderCreator(sc)
        return Setup(sc, creator, a)
    }
}
