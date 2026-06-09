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

class InventoryPolicyAbstractTest {

    /** Stub policy that records the parameters it was last told to apply. */
    private class StubPolicy(parent: ModelElement, name: String = "StubPolicy") :
        InventoryPolicyAbstract(parent, name) {
        var currentParams: DoubleArray = DoubleArray(0)
        var checkCount: Int = 0

        override fun setInitialPolicyParameters(parameters: DoubleArray) {
            myInitialPolicyParameters = parameters.copyOf()
            // Also push the initial values into the current state.
            currentParams = parameters.copyOf()
        }

        override fun checkInventory() { checkCount++ }

        override fun setPolicyParameters(parameters: DoubleArray) {
            currentParams = parameters.copyOf()
        }

        override fun getPolicyParameters(): DoubleArray = currentParams.copyOf()
    }

    @Test
    fun `policy can be constructed without an inventory association`() {
        val m = Model("IPA.NoInv")
        val sc = SupplyChainModel(m)
        val p = StubPolicy(sc)
        // Untriggered checkInventory is fine; the policy only fails when
        // some method actually dereferences `inventory`. Construction
        // itself must not throw.
        assertEquals(0, p.checkCount)
    }

    @Test
    fun `setInventory wires the policy to an inventory`() {
        val m = Model("IPA.Wire")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val p = StubPolicy(sc)
        val inv = Inventory(sc, item, p, initialOnHand = 5)
        // Construction wires it bidirectionally.
        assertSame(p, inv.inventoryPolicy)
    }

    @Test
    fun `setInitialPolicyParameters stores a copy`() {
        val m = Model("IPA.Init")
        val sc = SupplyChainModel(m)
        val p = StubPolicy(sc)
        val init = doubleArrayOf(3.0, 7.0)
        p.setInitialPolicyParameters(init)
        // Mutate the source; stored copy must not change.
        init[0] = 999.0
        assertEquals(3.0, p.getPolicyParameters()[0])
    }

    @Test
    fun `getPolicyParameters returns a snapshot`() {
        val m = Model("IPA.Snapshot")
        val sc = SupplyChainModel(m)
        val p = StubPolicy(sc)
        p.setInitialPolicyParameters(doubleArrayOf(1.0, 2.0))
        val snap = p.getPolicyParameters()
        snap[0] = 0.0
        // Snapshot mutation must not affect the stored state.
        assertEquals(1.0, p.getPolicyParameters()[0])
    }
}
