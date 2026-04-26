package ksl.simulation

import ksl.controls.ControlData
import ksl.controls.ControlType
import ksl.controls.ControlUpdateException
import ksl.examples.general.controls.Truck
import ksl.examples.general.controls.Van
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the KSL model-controls API.
 *
 * Covers all three control families (numeric, string, JSON) and the
 * export/import round-trip using the Van and Truck model elements from
 * VanControlsDemo.  No simulation replications are run — these tests
 * exercise only model construction and the Controls extraction API.
 *
 * Van controls (numeric):
 *   numSeats   INTEGER  [1, 18]
 *   numWheels  SHORT    [3,  8]
 *   price      DOUBLE   (unbounded)
 *   isStickShift BOOLEAN
 * Van controls (string):
 *   fuelType   allowedValues = [GASOLINE, DIESEL, ELECTRIC, HYBRID]
 *   colour     unconstrained
 *
 * Truck controls (JSON):
 *   axleWeights            List<Double>
 *   compartmentCapacities  Map<String, Double>
 */
class ModelControlsTest {

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private fun makeVanModel(numVans: Int = 3): Model {
        val m = Model("VanModel", autoCSVReports = false)
        repeat(numVans) { Van(m, "Van_$it") }
        return m
    }

    private fun makeTruckModel(numTrucks: Int = 3): Model {
        val m = Model("TruckModel", autoCSVReports = false)
        repeat(numTrucks) { Truck(m, "Truck_$it") }
        return m
    }

    private fun makeMixedModel(): Model {
        val m = Model("MixedModel", autoCSVReports = false)
        repeat(2) { Van(m, "Van_$it") }
        repeat(2) { Truck(m, "Truck_$it") }
        return m
    }

    // ── Group 1: Numeric controls — size and lookup ──────────────────────────

    @Test
    fun numericControlSizeEqualsExpectedCount() {
        // 3 vans × 4 numeric controls each (numSeats, numWheels, price, isStickShift)
        val controls = makeVanModel(3).controls()
        assertEquals(12, controls.size)
    }

    @Test
    fun numericControlLookupByKeyReturnsNonNull() {
        val controls = makeVanModel().controls()
        assertNotNull(controls.control("Van_0.numSeats"), "Van_0.numSeats must be findable")
    }

    @Test
    fun numericControlLookupForMissingKeyReturnsNull() {
        val controls = makeVanModel().controls()
        assertNull(controls.control("Van_99.nonExistent"))
    }

    @Test
    fun numericControlInitialValueMatchesDefault() {
        val controls = makeVanModel().controls()
        assertEquals(3.0, controls.control("Van_0.numSeats")!!.value, 0.0,
            "Default numSeats must be 3")
    }

    // ── Group 2: Numeric controls — write and clamp ──────────────────────────

    @Test
    fun settingNumericControlWithinBoundsUpdatesValue() {
        val controls = makeVanModel().controls()
        controls.control("Van_1.numSeats")!!.value = 7.0
        assertEquals(7.0, controls.control("Van_1.numSeats")!!.value, 0.0)
    }

    @Test
    fun settingNumericControlAboveUpperBoundClampsToUpperBound() {
        val controls = makeVanModel().controls()
        controls.control("Van_1.numSeats")!!.value = 100.0   // upperBound = 18
        assertEquals(18.0, controls.control("Van_1.numSeats")!!.value, 0.0,
            "Value 100 must be clamped to upperBound 18")
    }

    @Test
    fun settingBooleanControlToZeroMakesValueZero() {
        val controls = makeVanModel().controls()
        controls.control("Van_2.isStickShift")!!.value = 0.0
        assertEquals(0.0, controls.control("Van_2.isStickShift")!!.value, 0.0,
            "Boolean false must round-trip as 0.0")
    }

    @Test
    fun settingBooleanControlToOneMakesValueOne() {
        val controls = makeVanModel().controls()
        // Default is true (1.0); set to false then back to true
        controls.control("Van_0.isStickShift")!!.value = 0.0
        controls.control("Van_0.isStickShift")!!.value = 1.0
        assertEquals(1.0, controls.control("Van_0.isStickShift")!!.value, 0.0)
    }

    @Test
    fun controlKeysSetContainsExpectedKey() {
        val controls = makeVanModel().controls()
        assertTrue("Van_0.price" in controls.controlKeys())
    }

    // ── Group 3: String controls — size and lookup ───────────────────────────

    @Test
    fun stringControlSizeEqualsExpectedCount() {
        // 3 vans × 2 string controls each (fuelType, colour)
        val controls = makeVanModel(3).controls()
        assertEquals(6, controls.stringControlSize)
    }

    @Test
    fun stringControlLookupByKeyReturnsNonNull() {
        val controls = makeVanModel().controls()
        assertNotNull(controls.stringControl("Van_1.fuelType"))
    }

    @Test
    fun stringControlInitialValueMatchesDefault() {
        val controls = makeVanModel().controls()
        assertEquals("GASOLINE", controls.stringControl("Van_0.fuelType")!!.value)
    }

    // ── Group 4: String controls — valid and invalid updates ─────────────────

    @Test
    fun settingStringControlToAllowedValueUpdatesValue() {
        val controls = makeVanModel().controls()
        controls.stringControl("Van_1.fuelType")!!.value = "ELECTRIC"
        assertEquals("ELECTRIC", controls.stringControl("Van_1.fuelType")!!.value)
    }

    @Test
    fun settingStringControlToDisallowedValueThrowsControlUpdateException() {
        val controls = makeVanModel().controls()
        val fuel = controls.stringControl("Van_0.fuelType")!!
        assertThrows(ControlUpdateException::class.java) {
            fuel.value = "HYDROGEN"
        }
    }

    @Test
    fun failedStringControlUpdateLeavesValueUnchanged() {
        val controls = makeVanModel().controls()
        val fuel = controls.stringControl("Van_0.fuelType")!!
        try { fuel.value = "HYDROGEN" } catch (_: ControlUpdateException) {}
        assertEquals("GASOLINE", fuel.value, "Value must be unchanged after rejected update")
    }

    @Test
    fun unconstrainedStringControlAcceptsArbitraryValue() {
        val controls = makeVanModel().controls()
        controls.stringControl("Van_2.colour")!!.value = "MIDNIGHT BLUE"
        assertEquals("MIDNIGHT BLUE", controls.stringControl("Van_2.colour")!!.value)
    }

    @Test
    fun bulkStringControlUpdateReturnsCountOfSuccessfulApplications() {
        val controls = makeVanModel().controls()
        val applied = controls.setStringControlsFromMap(mapOf(
            "Van_0.fuelType" to "DIESEL",    // valid
            "Van_0.colour"   to "RED",       // valid
            "Van_1.fuelType" to "JET_FUEL",  // invalid — skipped
        ))
        assertEquals(2, applied, "Two valid entries must be applied")
    }

    @Test
    fun bulkStringControlUpdateSkipsInvalidEntryAndAppliesValid() {
        val controls = makeVanModel().controls()
        controls.setStringControlsFromMap(mapOf(
            "Van_0.fuelType" to "DIESEL",
            "Van_1.fuelType" to "JET_FUEL",
        ))
        assertEquals("DIESEL",   controls.stringControl("Van_0.fuelType")!!.value)
        assertEquals("GASOLINE", controls.stringControl("Van_1.fuelType")!!.value,
            "Invalid entry must leave Van_1.fuelType at its default")
    }

    // ── Group 5: JSON controls — size and lookup ──────────────────────────────

    @Test
    fun jsonControlSizeEqualsExpectedCount() {
        // 3 trucks × 2 JSON controls each (axleWeights, compartmentCapacities)
        val controls = makeTruckModel(3).controls()
        assertEquals(6, controls.jsonControlSize)
    }

    @Test
    fun jsonControlLookupByKeyReturnsNonNull() {
        val controls = makeTruckModel().controls()
        assertNotNull(controls.jsonControl("Truck_0.axleWeights"))
    }

    // ── Group 6: JSON controls — valid and invalid updates ────────────────────

    @Test
    fun settingJsonControlToValidJsonUpdatesValue() {
        val controls = makeTruckModel().controls()
        controls.jsonControl("Truck_0.axleWeights")!!.value = "[5000.0, 7000.0, 7000.0]"
        val v = controls.jsonControl("Truck_0.axleWeights")!!.value
        // The getter re-serializes via kotlinx.serialization, so compare by content not by exact string
        assertTrue(v.contains("5000") && v.contains("7000"),
            "axleWeights must reflect the assigned values (got: $v)")
    }

    @Test
    fun settingJsonControlToInvalidJsonThrowsControlUpdateException() {
        val controls = makeTruckModel().controls()
        val axles = controls.jsonControl("Truck_0.axleWeights")!!
        assertThrows(ControlUpdateException::class.java) {
            axles.value = "[not, valid, json]"
        }
    }

    @Test
    fun failedJsonControlUpdateLeavesValueUnchanged() {
        val controls = makeTruckModel().controls()
        val axles = controls.jsonControl("Truck_0.axleWeights")!!
        val original = axles.value
        try { axles.value = "[bad]" } catch (_: ControlUpdateException) {}
        assertEquals(original, axles.value, "Value must be unchanged after rejected JSON update")
    }

    @Test
    fun bulkJsonControlUpdateReturnsCountOfSuccessfulApplications() {
        val controls = makeTruckModel().controls()
        val applied = controls.setJsonControlsFromMap(mapOf(
            "Truck_0.axleWeights"           to "[3000.0, 5500.0]",              // valid
            "Truck_1.compartmentCapacities" to """{"fwd":10.0,"aft":20.0}""",  // valid
            "Truck_2.axleWeights"           to """{"bad":"type"}""",            // wrong type — skipped
        ))
        assertEquals(2, applied, "Two valid entries must be applied")
    }

    // ── Group 7: Export / import round-trip ───────────────────────────────────

    @Test
    fun exportAllTotalControlsMatchesSumOfFamilySizes() {
        val controls = makeMixedModel().controls()
        val export = controls.exportAll()
        val expected = controls.size + controls.stringControlSize + controls.jsonControlSize
        assertEquals(expected, export.totalControls,
            "exportAll totalControls must equal sum of the three family sizes")
    }

    @Test
    fun importAllFromJsonRestoresNumericValueAfterMutation() {
        val controls = makeMixedModel().controls()
        val snapshot = controls.exportAllAsJson()
        controls.control("Van_0.price")!!.value = 99999.0
        controls.importAllFromJson(snapshot)
        assertEquals(1.2345, controls.control("Van_0.price")!!.value, 1e-10,
            "importAllFromJson must restore the original price")
    }

    @Test
    fun importAllFromJsonRestoresStringValueAfterMutation() {
        val controls = makeMixedModel().controls()
        val snapshot = controls.exportAllAsJson()
        controls.stringControl("Van_0.fuelType")!!.value = "DIESEL"
        controls.importAllFromJson(snapshot)
        assertEquals("GASOLINE", controls.stringControl("Van_0.fuelType")!!.value,
            "importAllFromJson must restore the original fuelType")
    }

    @Test
    fun importAllFromJsonRestoresJsonValueAfterMutation() {
        val controls = makeMixedModel().controls()
        val snapshot = controls.exportAllAsJson()
        controls.jsonControl("Truck_0.axleWeights")!!.value = "[1.0, 2.0]"
        controls.importAllFromJson(snapshot)
        // After restoration the value reflects the original (3-element list)
        assertFalse(controls.jsonControl("Truck_0.axleWeights")!!.value.contains("1.0, 2.0"),
            "importAllFromJson must restore the original axleWeights, not the mutated value")
    }

    @Test
    fun cleanImportResultHasZeroFailuresAndZeroMissingKeys() {
        val controls = makeMixedModel().controls()
        val result = controls.importAllFromJson(controls.exportAllAsJson())
        assertFalse(result.hasFailures,     "Clean round-trip must have no failures")
        assertFalse(result.hasMissingKeys,  "Clean round-trip must have no missing keys")
        assertEquals(0, result.failureCount)
        assertEquals(0, result.missingKeyCount)
    }

    @Test
    fun cleanImportResultSuccessCountEqualsControlCount() {
        val controls = makeMixedModel().controls()
        val totalExpected = controls.size + controls.stringControlSize + controls.jsonControlSize
        val result = controls.importAllFromJson(controls.exportAllAsJson())
        assertEquals(totalExpected, result.successCount,
            "successCount must equal the total number of controls in the model")
    }

    // ── Group 8: Missing key handling ─────────────────────────────────────────

    @Test
    fun importWithPhantomKeyReportsItInMissingKeys() {
        val controls = makeMixedModel().controls()
        val phantomKey = "Phantom_99.nonExistent"
        val export = controls.exportAll().let { e ->
            e.copy(numericControls = e.numericControls + ControlData(
                controlType  = ControlType.DOUBLE,
                value        = 0.0,
                keyName      = phantomKey,
                lowerBound   = Double.NEGATIVE_INFINITY,
                upperBound   = Double.POSITIVE_INFINITY,
                elementName  = "Phantom_99",
                elementId    = -1,
                elementType  = "Phantom",
                propertyName = "nonExistent",
                comment      = "",
                modelName    = "MixedModel",
            ))
        }
        val result = controls.importAll(export)
        assertTrue(phantomKey in result.missingKeys,
            "The phantom key must appear in ControlImportResult.missingKeys")
        assertEquals(1, result.missingKeyCount)
    }

    @Test
    fun importWithPhantomKeyDoesNotCountPhantomInSuccessCount() {
        val controls = makeMixedModel().controls()
        val export = controls.exportAll().let { e ->
            e.copy(numericControls = e.numericControls + ControlData(
                controlType  = ControlType.DOUBLE,
                value        = 0.0,
                keyName      = "Ghost.missing",
                lowerBound   = Double.NEGATIVE_INFINITY,
                upperBound   = Double.POSITIVE_INFINITY,
                elementName  = "Ghost",
                elementId    = -1,
                elementType  = "Ghost",
                propertyName = "missing",
                comment      = "",
                modelName    = "MixedModel",
            ))
        }
        val result = controls.importAll(export)
        val expectedSuccess = controls.size + controls.stringControlSize + controls.jsonControlSize
        assertEquals(expectedSuccess, result.successCount,
            "Phantom key must not inflate successCount")
    }
}
