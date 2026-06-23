package ksl.modeling.supplychain

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemandFillerAbstractTest {

    @Test
    fun `availability reflects construction parameter`() {
        val (_, sc) = freshModel()
        val a = TestFiller(sc, initialAvailability = true)
        val b = TestFiller(sc, initialAvailability = false)
        assertTrue(a.isAvailable)
        assertFalse(b.isAvailable)
    }

    @Test
    fun `carrier and preparer setters round-trip`() {
        val (_, sc) = freshModel()
        val f = TestFiller(sc)
        assertNull(f.demandCarrier)
        assertNull(f.demandPreparer)
        val carrier = object : DemandCarrierIfc {
            override fun transportDemand(demand: SupplyChainModel.Demand) {}
            override fun canShip(demand: SupplyChainModel.Demand) = true
        }
        val preparer = DemandPreparerIfc { /* no-op */ }
        f.demandCarrier = carrier
        f.demandPreparer = preparer
        assertSame(carrier, f.demandCarrier)
        assertSame(preparer, f.demandPreparer)
    }

    @Test
    fun `id and name come from ModelElement`() {
        val (_, sc) = freshModel()
        val f = TestFiller(sc, name = "Filler-X")
        assertEquals("Filler-X", f.name)
        assertTrue(f.id > 0)
    }

    private fun freshModel(): Pair<Model, SupplyChainModel> {
        val m = Model("DemandFillerAbstractTest")
        return m to SupplyChainModel(m)
    }

    /** Concrete subclass for tests. All abstract methods stay minimal. */
    private class TestFiller(
        parent: SupplyChainModel,
        initialAvailability: Boolean = true,
        name: String? = null,
    ) : DemandFillerAbstract(parent, initialAvailability, name) {
        override fun receive(demand: SupplyChainModel.Demand) = error("not used")
        override fun fillDemand(demand: SupplyChainModel.Demand) = error("not used")
        override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
        override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean = true
        override fun canFillItemType(type: ItemType): Boolean = true
        override val itemTypes: Collection<ItemType> = emptyList()
        override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
            DemandStatusCode.ImmediateFill
        override fun willReject(demand: SupplyChainModel.Demand): Boolean = false
    }
}
