package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LeadTimeDemandFillerTest {

    @Test
    fun `addLeadTime registers an item type the filler can fill`() {
        val (_, _, filler, item) = setup()
        assertFalse(filler.canFillItemType(item))
        filler.addLeadTime(item, ConstantRV(2.0))
        assertTrue(filler.canFillItemType(item))
        assertEquals(setOf(item), filler.itemTypes.toSet())
    }

    @Test
    fun `addLeadTime replaces the source for an existing item type`() {
        val (_, _, filler, item) = setup()
        filler.addLeadTime(item, ConstantRV(1.0))
        val before = filler.getLeadTime(item)!!.value
        filler.addLeadTime(item, ConstantRV(5.0))
        val after = filler.getLeadTime(item)!!.value
        assertEquals(1.0, before)
        assertEquals(5.0, after)
        // Note: KSL's RandomVariable.initialRandomSource setter may
        // re-instantiate the supplied RVariableIfc if its stream provider
        // differs, so identity is not preserved; we assert by value.
    }

    @Test
    fun `removeLeadTime drops the item type`() {
        val (_, _, filler, item) = setup()
        filler.addLeadTime(item, ConstantRV(1.0))
        assertTrue(filler.canFillItemType(item))
        filler.removeLeadTime(item)
        assertFalse(filler.canFillItemType(item))
    }

    @Test
    fun `unavailable filler rejects a received demand with FillerUnavailable`() {
        val (_, sc, filler, item) = setup(initialAvailability = false)
        filler.addLeadTime(item, ConstantRV(1.0))
        val d = sc.createDemand(item, 1)
        d.setFiller(filler)
        d.sent()
        filler.receive(d)
        assertSame(sc.rejected, d.demandState)
        assertEquals(DemandStatusCode.FillerUnavailable, d.status)
    }

    @Test
    fun `unsupported item type rejects with ItemTypeMismatch`() {
        val (_, sc, filler, _) = setup()
        val other = ItemType(sc, name = "Unknown")
        val d = sc.createDemand(other, 1)
        d.setFiller(filler)
        d.sent()
        filler.receive(d)
        assertSame(sc.rejected, d.demandState)
        assertEquals(DemandStatusCode.ItemTypeMismatch, d.status)
    }

    @Test
    fun `successful receive places demand in received state`() {
        val (_, sc, filler, item) = setup()
        filler.addLeadTime(item, ConstantRV(1.0))
        val d = sc.createDemand(item, 1)
        d.setFiller(filler)
        d.sent()
        filler.receive(d)
        assertSame(sc.received, d.demandState)
        assertEquals(DemandStatusCode.ImmediateFill, d.status)
    }

    @Test
    fun `determineRequestStatus reflects availability and fillability`() {
        val (_, sc, filler, item) = setup()
        filler.addLeadTime(item, ConstantRV(1.0))
        val ok = sc.createDemand(item, 1)
        val notFillable = sc.createDemand(ItemType(sc, name = "Other"), 1)
        assertEquals(DemandStatusCode.ImmediateFill,
            filler.determineRequestStatus(ok))
        assertEquals(DemandStatusCode.ItemTypeMismatch,
            filler.determineRequestStatus(notFillable))
        assertFalse(filler.willReject(ok))
        assertTrue(filler.willReject(notFillable))
    }

    @Test
    fun `negotiate transitions demand to NEGOTIATING and returns a message`() {
        val (_, sc, filler, item) = setup()
        filler.addLeadTime(item, ConstantRV(1.0))
        val d = sc.createDemand(item, 7)
        d.setFiller(filler)
        val msg = filler.negotiate(d)
        assertNotNull(msg)
        assertSame(sc.negotiating, d.demandState)
        assertEquals(true, msg.canFillItemType)
        assertEquals(7, msg.requestFillAmount)
        assertSame(filler, msg.demandFiller)
        assertEquals(DemandStatusCode.ImmediateFill, msg.requestStatus)
    }

    @Test
    fun `end-to-end simulation drives demand to delivered after lead time`() {
        val m = Model("LeadTimeDemandFillerEndToEnd")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = LeadTimeDemandFiller(sc)
        filler.addLeadTime(item, ConstantRV(2.5))
        // Capture the demand created during initialize() so we can assert.
        val captured = mutableListOf<SupplyChainModel.Demand>()
        Kickoff(sc, filler, item, captured)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        val d = captured.last()
        assertSame(sc.stored, d.demandState)
        assertEquals(3, d.amountFilled)
        assertTrue(d.isFilled)
        assertEquals(2.5, d.fillingTime)
    }

    private data class Setup(
        val model: Model,
        val sc: SupplyChainModel,
        val filler: LeadTimeDemandFiller,
        val item: ItemType,
    )

    private fun setup(initialAvailability: Boolean = true): Setup {
        val m = Model("LeadTimeDemandFillerTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = TestLTDF(sc, initialAvailability)
        return Setup(m, sc, filler, item)
    }

    /** Test subclass to exercise protected setAvailability(). */
    private class TestLTDF(
        parent: SupplyChainModel,
        initialAvailability: Boolean,
    ) : LeadTimeDemandFiller(parent, name = "TestLTDF") {
        init { setAvailability(initialAvailability) }
    }

    /**
     * Drives a fresh demand through the filler at each replication start.
     * Appending to [captured] gives the test a reference for assertions.
     */
    private class Kickoff(
        sc: SupplyChainModel,
        private val filler: LeadTimeDemandFiller,
        private val item: ItemType,
        private val captured: MutableList<SupplyChainModel.Demand>,
    ) : ModelElement(sc, "Kickoff") {
        private val sc: SupplyChainModel = sc
        override fun initialize() {
            super.initialize()
            val d = sc.createDemand(item, 3)
            d.setFiller(filler)
            d.sent()
            filler.receive(d)
            filler.fillDemand(d)
            captured.add(d)
        }
    }
}
