package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemandLoadTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    private fun build(): Triple<SupplyChainModel, ItemType, TestSender> {
        val m = Model("DLTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A", weight = 2.0, cube = 3.0)
        return Triple(sc, item, TestSender("S1"))
    }

    @Test
    fun `empty load reports empty`() {
        val (sc, _, _) = build()
        val load = sc.createDemandLoad("L1")
        assertTrue(load.isEmpty)
        assertEquals(0, load.size)
        assertEquals(0.0, load.weight)
        assertEquals(0.0, load.cube)
        assertNull(load.destination)
    }

    @Test
    fun `adding a demand sets weight, cube, and destination`() {
        val (sc, item, sender) = build()
        val load = sc.createDemandLoad("L1")
        val d = sc.createDemand(item, 4)
        d.setDemandSender(sender)
        // Drive d to a state where weight/cube reflect filled qty.
        // (weight/cube are amount-filled * itemType.weight/cube.)
        // Without driving the state machine, amountFilled is 0, so the
        // load's weight/cube end up 0. That's the correct port-level
        // behavior for an unfilled demand.
        load.addDemand(d)
        assertEquals(1, load.size)
        assertSame(sender, load.destination)
        assertTrue(d in load.demands)
        // amountFilled == 0 so per-demand weight/cube are 0
        assertEquals(0.0, load.weight)
        assertEquals(0.0, load.cube)
    }

    @Test
    fun `cannot add the same demand twice`() {
        val (sc, item, sender) = build()
        val load = sc.createDemandLoad("L1")
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        load.addDemand(d)
        assertThrows<IllegalArgumentException> { load.addDemand(d) }
    }

    @Test
    fun `cannot add demand with null sender`() {
        val (sc, item, _) = build()
        val load = sc.createDemandLoad("L1")
        val d = sc.createDemand(item, 1)
        // demandSender stays null
        assertThrows<IllegalStateException> { load.addDemand(d) }
    }

    @Test
    fun `cannot mix destinations on one load`() {
        val (sc, item, sender) = build()
        val other = TestSender("S2")
        val load = sc.createDemandLoad("L1")
        val item2 = ItemType(sc, name = "B")
        val d1 = sc.createDemand(item, 1)
        d1.setDemandSender(sender)
        val d2 = sc.createDemand(item2, 1)
        d2.setDemandSender(other)
        load.addDemand(d1)
        assertThrows<IllegalArgumentException> { load.addDemand(d2) }
    }
}
