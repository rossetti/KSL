package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.supplychain.inventory.InventoryPolicyAbstract
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Controls-annotation coverage for the supply-chain package (the supplychain-controls
 * plan). Per tier: a discovery test (the expected keys appear via model.controls()) and
 * a round-trip test (setting by key writes through to the property, with numeric bound
 * clamping). Controls configure replication INITIAL conditions; current values are
 * re-seeded from initials at beforeReplication().
 */
class SupplyChainControlsTest {

    /** A no-op policy — never requests replenishment. */
    private class NullPolicy(parent: ModelElement) :
        InventoryPolicyAbstract(parent, "NullPolicy") {
        override fun setInitialPolicyParameters(parameters: DoubleArray) {
            myInitialPolicyParameters = parameters
        }
        override fun checkInventory() { /* never order */ }
        override fun setPolicyParameters(parameters: DoubleArray) { /* no-op */ }
        override fun getPolicyParameters(): DoubleArray = DoubleArray(0)
    }

    private data class Fixture(
        val model: Model,
        val sc: SupplyChainModel,
        val item: ItemType,
        val inv: Inventory,
    )

    private fun fixture(): Fixture {
        val m = Model("SupplyChainControlsTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU")
        val policy = NullPolicy(sc)
        val inv = Inventory(sc, item, policy, initialOnHand = 10, name = "Inv")
        return Fixture(m, sc, item, inv)
    }

    // ── Tier 2: Inventory + ItemType ──────────────────────────────────────────

    @Test
    @DisplayName("Tier 2: inventory and item-type control keys are discoverable")
    fun tier2KeysDiscoverable() {
        val f = fixture()
        val keys = f.model.controls().controlKeys()
        val expected = listOf(
            "Inv.initialOnHand",
            "Inv.permitPartialFilling",
            "Inv.permitBackLogging",
            "Inv.mayPartiallyFillDemands",
            "SKU.unitCost"
        )
        for (key in expected) {
            assertTrue(key in keys) { "expected control key '$key'; got $keys" }
        }
        // Deliberately excluded: derived from backlog-policy attachment.
        assertFalse(keys.any { it.endsWith(".allowBackLogging") }) {
            "allowBackLogging must not be a control (derived state)"
        }
    }

    @Test
    @DisplayName("Tier 2: controls write through to properties and clamp to bounds")
    fun tier2RoundTripAndClamping() {
        val f = fixture()
        val controls = f.model.controls()

        // Numeric write-through and clamping (initialOnHand has lowerBound = 0).
        val onHand = controls.control("Inv.initialOnHand")
        assertNotNull(onHand)
        onHand!!.value = 25.0
        assertEquals(25, f.inv.initialOnHand)
        onHand.value = -5.0   // clamps, does not throw
        assertEquals(0, f.inv.initialOnHand)

        // unitCost clamps at 0.
        val cost = controls.control("SKU.unitCost")
        assertNotNull(cost)
        cost!!.value = 7.5
        assertEquals(7.5, f.item.unitCost, 0.0)
        cost.value = -1.0
        assertEquals(0.0, f.item.unitCost, 0.0)

        // Booleans are numeric controls set via 1.0 / 0.0.
        val backLog = controls.control("Inv.permitBackLogging")
        assertNotNull(backLog)
        backLog!!.value = 0.0
        assertFalse(f.inv.permitBackLogging)
        backLog.value = 1.0
        assertTrue(f.inv.permitBackLogging)

        val partial = controls.control("Inv.mayPartiallyFillDemands")
        assertNotNull(partial)
        partial!!.value = 0.0
        assertFalse(f.inv.mayPartiallyFillDemands)
    }
}
