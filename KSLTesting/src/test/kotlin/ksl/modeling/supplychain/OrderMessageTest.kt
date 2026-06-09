package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderMessageTest {

    @Test
    fun `empty message reports not fillable`() {
        val om = OrderMessage(TestOrderFiller(), 0.0)
        assertTrue(om.isEmpty)
        assertEquals(0, om.size)
        assertFalse(om.canFillItemTypes)
    }

    @Test
    fun `all-fillable messages keep canFillItemTypes true`() {
        val om = OrderMessage(TestOrderFiller(), 0.0)
        om.add(makeMessage(canFill = true))
        om.add(makeMessage(canFill = true))
        assertEquals(2, om.size)
        assertTrue(om.canFillItemTypes)
    }

    @Test
    fun `one non-fillable message flips canFillItemTypes false`() {
        val om = OrderMessage(TestOrderFiller(), 0.0)
        om.add(makeMessage(canFill = true))
        om.add(makeMessage(canFill = false))
        assertFalse(om.canFillItemTypes)
    }

    @Test
    fun `null entry flips canFillItemTypes false without NPE (Java bug fix)`() {
        val om = OrderMessage(TestOrderFiller(), 0.0)
        om.add(makeMessage(canFill = true))
        om.add(null)
        assertFalse(om.canFillItemTypes)
        assertEquals(2, om.size)
        assertNull(om.get(1))
    }

    @Test
    fun `flag does not reset after subsequent fillable adds`() {
        val om = OrderMessage(TestOrderFiller(), 0.0)
        om.add(makeMessage(canFill = false))
        om.add(makeMessage(canFill = true))
        assertFalse(om.canFillItemTypes)
    }

    @Test
    fun `demandMessages view reflects insertions in order`() {
        val om = OrderMessage(TestOrderFiller(), 0.0)
        val a = makeMessage(canFill = true)
        val b = makeMessage(canFill = false)
        om.add(a); om.add(b)
        assertEquals(listOf<DemandMessageIfc?>(a, b), om.demandMessages)
    }

    private fun makeMessage(canFill: Boolean): DemandMessage =
        DemandMessage(
            demandFiller = TestDemandFiller(),
            timeStamp = 0.0,
            canFillItemType = canFill,
        )

    private class TestDemandFiller : NoOpDemandFiller(name = "df")
    private class TestOrderFiller : NoOpOrderFiller(name = "of")
}
