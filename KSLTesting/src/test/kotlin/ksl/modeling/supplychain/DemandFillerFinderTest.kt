package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemandFillerFinderTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    @Test
    fun `empty finder reports empty and findDemandFiller returns null`() {
        val finder = DemandFillerFinder()
        assertTrue(finder.isEmpty)
        assertEquals(0, finder.size)
        val (_, d) = freshDemand()
        assertNull(finder.findDemandFiller(d))
    }

    @Test
    fun `put then findDemandFiller resolves the supplier`() {
        val finder = DemandFillerFinder()
        val sender = TestSender("S1")
        val supplier = NoOpDemandFiller(name = "Sup1")
        finder.put(sender, supplier)
        assertEquals(1, finder.size)
        assertFalse(finder.isEmpty)
        assertTrue(finder.containsCustomer(sender))
        assertTrue(finder.containsSupplier(supplier))

        val (_, d) = freshDemand()
        d.setDemandSender(sender)
        assertSame(supplier, finder.findDemandFiller(d))
    }

    @Test
    fun `multiple customers can share one supplier`() {
        val finder = DemandFillerFinder()
        val s1 = TestSender("S1")
        val s2 = TestSender("S2")
        val supplier = NoOpDemandFiller(name = "Sup")
        finder.put(s1, supplier)
        finder.put(s2, supplier)
        assertEquals(2, finder.size)
        assertTrue(finder.containsSupplier(supplier))

        val (_, d1) = freshDemand()
        d1.setDemandSender(s1)
        val (_, d2) = freshDemand()
        d2.setDemandSender(s2)
        assertSame(supplier, finder.findDemandFiller(d1))
        assertSame(supplier, finder.findDemandFiller(d2))
    }

    @Test
    fun `put returns prior supplier when the customer is reassigned`() {
        val finder = DemandFillerFinder()
        val sender = TestSender("S1")
        val sup1 = NoOpDemandFiller(name = "Sup1")
        val sup2 = NoOpDemandFiller(name = "Sup2")
        assertNull(finder.put(sender, sup1))
        assertSame(sup1, finder.put(sender, sup2))
    }

    @Test
    fun `customers iteration is insertion-ordered`() {
        val finder = DemandFillerFinder()
        val a = TestSender("A")
        val b = TestSender("B")
        val c = TestSender("C")
        finder.put(b, NoOpDemandFiller())
        finder.put(a, NoOpDemandFiller())
        finder.put(c, NoOpDemandFiller())
        assertEquals(listOf(b, a, c), finder.customers.toList())
    }

    @Test
    fun `containsCustomer is false for an unregistered sender`() {
        val finder = DemandFillerFinder()
        assertFalse(finder.containsCustomer(TestSender("X")))
    }

    private fun freshDemand(): Pair<SupplyChainModel, SupplyChainModel.Demand> {
        val m = Model("DFFTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        return sc to sc.createDemand(item, 1)
    }
}
