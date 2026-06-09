package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackLogPolicyAbstractTest {

    /** Minimal stubs to construct an Inventory + BackLog policy chain. */
    private class StubPolicy(parent: ModelElement) :
        InventoryPolicyAbstract(parent, "StubPolicy") {
        override fun setInitialPolicyParameters(parameters: DoubleArray) {
            myInitialPolicyParameters = parameters
        }
        override fun checkInventory() { /* no-op */ }
        override fun setPolicyParameters(parameters: DoubleArray) { /* no-op */ }
        override fun getPolicyParameters(): DoubleArray = DoubleArray(0)
    }

    private class StubBackLog(inv: Inventory, name: String = "StubBL") :
        BackLogPolicyAbstract(inv, name) {
        var backlogCalls: Int = 0
        var fillCalls: Int = 0
        override fun backlog(demand: SupplyChainModel.Demand) {
            backlogCalls++
            // Track the amount in the time-weighted response.
            amtBackLogged.increment(demand.remainingDemand.toDouble())
        }
        override fun fillBackLogs() { fillCalls++ }
        override val numberOfDemandsBackLogged: Int get() = backlogCalls - fillCalls
        override fun getBackLogStatistics(): BackLogStatisticsIfc =
            error("not exercised in these tests")
        override val id: Int = 0
        override var label: String? = null
    }

    @Test
    fun `constructor establishes the bidirectional link with Inventory`() {
        val m = Model("BLP.Wire")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val inv = Inventory(sc, item, StubPolicy(sc), initialOnHand = 5)
        val bl = StubBackLog(inv)
        assertSame(bl, inv.backLogPolicy)
        assertTrue(inv.allowBackLogging)
    }

    @Test
    fun `amountBackLogged starts at zero`() {
        val m = Model("BLP.Zero")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val inv = Inventory(sc, item, StubPolicy(sc))
        val bl = StubBackLog(inv)
        assertEquals(0, bl.amountBackLogged)
    }

    @Test
    fun `second backlog policy attach is a no-op`() {
        val m = Model("BLP.Once")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val inv = Inventory(sc, item, StubPolicy(sc))
        val bl1 = StubBackLog(inv, "BL1")
        val bl2 = StubBackLog(inv, "BL2")
        // The first one wins; bl2 is constructed (its init runs) but
        // inventory.setBackLogPolicy guards on existing policy and
        // refuses the second attach.
        assertSame(bl1, inv.backLogPolicy)
    }
}
