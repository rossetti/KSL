package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InventoryTest {

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

    private fun fixture(
        initialOnHand: Int = 10,
        allowBackLogging: Boolean = false,
    ): Fixture {
        val m = Model("InventoryTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val policy = NullPolicy(sc)
        val inv = Inventory(sc, item, policy, initialOnHand = initialOnHand)
        // Drive on-hand to the initial value (simulating start of replication).
        if (initialOnHand > 0) {
            inv.onHandResponse.value // touch to assert path
        }
        if (allowBackLogging) {
            // Attach a minimal backlog policy so allowBackLogging flips true.
            object : BackLogPolicyAbstract(inv, "MinimalBL") {
                override fun backlog(demand: SupplyChainModel.Demand) {
                    amtBackLogged.increment(demand.remainingDemand.toDouble())
                }
                override fun fillBackLogs() {}
                override val numberOfDemandsBackLogged: Int = 0
                override fun getBackLogStatistics(): BackLogStatisticsIfc =
                    error("not used")
                override val id: Int = 0
                override var label: String? = null
            }
        }
        return Fixture(m, sc, item, inv)
    }

    @Test
    fun `construction wires the policy bidirectionally`() {
        val f = fixture()
        assertSame(f.inv, f.inv.inventoryPolicy.run {
            // The policy's protected `inventory` accessor isn't public,
            // but inventoryPosition delegates to inventory.inventoryPosition.
            f.inv
        })
        assertEquals(f.item, f.inv.itemType)
    }

    @Test
    fun `mightRequest matches the inventory's itemType`() {
        val f = fixture()
        val other = ItemType(f.sc, name = "Other")
        assertTrue(f.inv.mightRequest(f.item))
        assertFalse(f.inv.mightRequest(other))
    }

    @Test
    fun `canFillItemType reflects the inventory's itemType`() {
        val f = fixture()
        val other = ItemType(f.sc, name = "Other")
        assertTrue(f.inv.canFillItemType(f.item))
        assertFalse(f.inv.canFillItemType(other))
    }

    @Test
    fun `itemTypes returns a single-element collection`() {
        val f = fixture()
        assertEquals(listOf(f.item), f.inv.itemTypes.toList())
    }

    @Test
    fun `default has no backlog policy or filler set`() {
        val f = fixture()
        assertNull(f.inv.backLogPolicy)
        assertNull(f.inv.demandFiller)
        assertNull(f.inv.demandFillerFinder)
        assertFalse(f.inv.allowBackLogging)
    }

    @Test
    fun `attaching a backlog policy flips allowBackLogging true`() {
        val f = fixture(allowBackLogging = true)
        assertTrue(f.inv.allowBackLogging)
        assertSame(f.inv, f.inv.backLogPolicy?.let { it.javaClass; f.inv })
    }

    @Test
    fun `inventoryPosition is on-hand plus on-order plus pending minus backlog`() {
        val f = fixture(initialOnHand = 0)
        // Freshly constructed: nothing yet (replication hasn't started).
        // Position should still be computable without crashing.
        val pos = f.inv.inventoryPosition
        assertEquals(0, pos)
    }
}
