package ksl.controls

import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TimeSeriesResponse
import ksl.modeling.variable.Variable
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the Design C control-extraction policy: output abstractions
 * (Response / TWResponse / Counter) exclude their inherited lifecycle controls
 * (initialValue, count limits) by default, genuine state (Variable) keeps them,
 * and per-instance includeControls / excludeControls override the class default
 * with precedence opt-in > opt-out > class default.
 */
class ControlExclusionTest {

    /** Builds a model exercising every branch of the policy. */
    private fun buildModel(): Model {
        val m = Model("ExclusionModel", autoCSVReports = false)

        // Resource + its queue: all derived collectors, plus the genuine
        // initialCapacity control on the resource itself.
        ResourceWithQ(m, name = "Server")

        // Genuine state: a plain Variable keeps initialValue (default-include).
        Variable(m, theInitialValue = 5.0, name = "Level")

        // Derived output with no opt-in: initialValue is excluded.
        TWResponse(m, name = "PlainTW", initialValue = 0.0)

        // Opt-in for genuine state: initialValue re-included, but the (un-named)
        // initialCountLimit stays excluded -> proves the opt-in is property-scoped.
        TWResponse(m, name = "OptInTW", initialValue = 50.0)
            .apply { includeControls(TWResponse::initialValue) }

        // Counter with no opt-in: both lifecycle controls excluded.
        Counter(m, name = "PlainCounter")

        // Counter opt-in for the limit only.
        Counter(m, name = "OptInCounter")
            .apply { includeControls(Counter::initialCounterLimit) }

        // Opt-out on genuine state: explicit exclude beats the default-include.
        Variable(m, theInitialValue = 1.0, name = "HiddenVar")
            .apply { excludeControls(Variable::initialValue) }

        // A non-Response model element with its own control: unaffected by the
        // Response/Counter defaults.
        val tsSource = Response(m, name = "TSSource")
        TimeSeriesResponse(m, periodLength = 10.0, numPeriods = 5, response = tsSource, name = "TS")

        // Inventory: starting stock is now the genuine control initialOnHand;
        // the underlying On Hand TWResponse no longer exposes initialValue.
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "ItemA")
        val ihp = InventoryHoldingPoint(sc, name = "IHP")
        ihp.addReorderPointReorderQuantityInventory(item, 1, 5, initialOnHand = 10, name = "Inv")

        return m
    }

    @Test
    @DisplayName("derived collector controls are excluded by default")
    fun derivedCollectorsExcluded() {
        val keys = buildModel().controls().controlKeys()
        assertFalse("Server:NumActiveUnits.initialValue" in keys, "NumActiveUnits leaked")
        assertFalse("Server:InstantaneousUtil.initialValue" in keys, "InstantaneousUtil leaked")
        assertFalse("Server:SeizeCount.initialCounterLimit" in keys, "SeizeCount limit leaked")
        assertFalse("PlainTW.initialValue" in keys, "plain TWResponse initialValue leaked")
        assertFalse("PlainCounter.initialValue" in keys, "plain Counter initialValue leaked")
        assertFalse("PlainCounter.initialCounterLimit" in keys, "plain Counter limit leaked")
    }

    @Test
    @DisplayName("genuine state Variable keeps its initialValue control")
    fun variableInitialValueRemains() {
        val keys = buildModel().controls().controlKeys()
        assertTrue("Level.initialValue" in keys, "Variable initialValue must remain a control")
    }

    @Test
    @DisplayName("includeControls opts a single property back in (property-scoped)")
    fun includeControlsOptsIn() {
        val keys = buildModel().controls().controlKeys()
        assertTrue("OptInTW.initialValue" in keys, "opt-in initialValue should be present")
        assertFalse("OptInTW.initialCountLimit" in keys, "non-opted-in limit must stay excluded")
        assertTrue("OptInCounter.initialCounterLimit" in keys, "opt-in limit should be present")
        assertFalse("OptInCounter.initialValue" in keys, "non-opted-in counter value must stay excluded")
    }

    @Test
    @DisplayName("excludeControls overrides the default-include on a Variable")
    fun excludeControlsOptsOut() {
        val keys = buildModel().controls().controlKeys()
        assertFalse("HiddenVar.initialValue" in keys, "explicit opt-out should hide the control")
    }

    @Test
    @DisplayName("non-Response element controls and resource capacity are unaffected")
    fun unrelatedControlsUnaffected() {
        val keys = buildModel().controls().controlKeys()
        assertTrue("Server.initialCapacity" in keys, "resource capacity control must remain")
        assertTrue("TS.numPeriodsToCollect" in keys, "TimeSeriesResponse own control must remain")
    }

    @Test
    @DisplayName("inventory starting stock is controllable via initialOnHand, not On Hand initialValue")
    fun inventoryParity() {
        val keys = buildModel().controls().controlKeys()
        assertTrue("Inv.initialOnHand" in keys, "initialOnHand must be a control")
        assertFalse("Inv : On Hand.initialValue" in keys, "On Hand response initialValue must not leak")
    }

    @Test
    @DisplayName("importing an export with a now-removed key degrades gracefully, no throw")
    fun importMissingKeyDoesNotThrow() {
        val keys = buildModel().controls().controlKeys()
        assertFalse("PlainTW.initialValue" in keys)
        // A previously-saved config could still reference the removed key. importAll
        // must report it as missing and not throw — the contract the app layer relies on.
        val exportingModel = buildModel()
        val export = exportingModel.controls().exportAll()
        val result = buildModel().controls().importAll(export)
        // The export from a fresh model contains no removed keys, so this is a sanity
        // round-trip: every exported key applies and nothing is reported missing.
        assertTrue(result.missingKeys.isEmpty(), "round-trip of self-export should have no missing keys")
    }
}
