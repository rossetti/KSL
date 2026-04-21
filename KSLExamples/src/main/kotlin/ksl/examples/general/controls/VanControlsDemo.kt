package ksl.examples.general.controls

import ksl.controls.ControlType
import ksl.controls.ControlUpdateException
import ksl.controls.KSLControl
import ksl.controls.KSLJsonControl
import ksl.controls.KSLStringControl
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * A simple model element with numeric, boolean, and string controls used to
 * demonstrate the `@KSLControl` and `@KSLStringControl` annotations together
 * with the [ksl.controls.Controls] extraction API.
 *
 * Each annotated property becomes a controllable parameter that can be read and
 * written through [ksl.controls.Controls] without touching the class directly.
 */
class Van(parent: ModelElement, name:String) : ModelElement(parent, name) {

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        name        = "numberOfSeats",
        lowerBound  = 1.0,
        upperBound  = 18.0,
        comment     = "0 seats == autonomous driving ?"
    )
    var numSeats: Int = 3
        set(n) {
            require(n >= 1) { "The number of seats must be >= 1" }
            field = n
        }

    @set:KSLControl(controlType = ControlType.SHORT, lowerBound = 3.0, upperBound = 8.0)
    var numWheels: Short = 4

    @set:KSLControl(controlType = ControlType.DOUBLE)
    var price: Double = 1.2345

    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var isStickShift: Boolean = true

    @set:KSLStringControl(
        allowedValues = ["GASOLINE", "DIESEL", "ELECTRIC", "HYBRID"],
        comment       = "Fuel type for the van"
    )
    var fuelType: String = "GASOLINE"

    @set:KSLStringControl(comment = "Exterior paint colour (unconstrained)")
    var colour: String = "WHITE"
}

// ── Demo 1: numeric and boolean KSLControl extraction ────────────────────────

/**
 * Demonstrates extraction and mutation of numeric and boolean controls.
 *
 * Creates a fleet of [Van] instances, extracts all controls via
 * `model.controls()`, and shows:
 * - Reading the current value of a control by key.
 * - Writing a value that is clamped to the declared bounds.
 * - Writing a boolean control via its 1.0 / 0.0 convention.
 * - Printing the full control map as a JSON string.
 */
fun demoNumericControls() {
    val model = Model("Van Model — Numeric")
    repeat(5) { Van(model, "Van_$it") }
    val controls = model.controls()

    println("=== Numeric controls (${controls.size}) ===")
    println(controls.controlDataAsString())

    // Value 100 is clamped to the declared upperBound of 18
    val seats = controls.control("Van_1.numSeats")
    println("Before: $seats")
    seats?.value = 100.0
    println("After setting 100 (clamped to 18): $seats")

    // Boolean via 1.0 / 0.0 convention
    val shift = controls.control("Van_2.isStickShift")
    println("\nBefore: $shift")
    shift?.value = 0.0
    println("After setting 0.0 (false): $shift")

    println("\n=== Numeric controls map (JSON) ===")
    println(controls.controlsMapAsJsonString())
}

// ── Demo 2: string KSLStringControl extraction ────────────────────────────────

/**
 * Demonstrates extraction and mutation of string controls.
 *
 * Shows:
 * - Reading initial values and allowed-values constraints.
 * - Setting a valid value.
 * - Attempting an invalid value and catching [ControlUpdateException].
 * - Setting an unconstrained string control freely.
 * - Bulk-setting string controls via `setStringControlsFromMap`.
 */
fun demoStringControls() {
    val model = Model("Van Model — String")
    repeat(3) { Van(model, "Van_$it") }
    val controls = model.controls()

    println("=== String controls (${controls.stringControlSize}) ===")
    println(controls.stringControlDataAsString())

    // Valid assignment within allowedValues
    val fuel = controls.stringControl("Van_1.fuelType")
    println("Initial fuelType: ${fuel?.value}")
    fuel?.value = "ELECTRIC"
    println("After setting ELECTRIC: ${fuel?.value}")

    // Invalid assignment — caught and reported
    println("\nAttempting to set fuelType to HYDROGEN (not in allowedValues):")
    try {
        fuel?.value = "HYDROGEN"
    } catch (e: ControlUpdateException) {
        println("  Caught ControlUpdateException: ${e.message}")
        println("  Control key: ${e.controlKey}")
        println("  Attempted value: ${e.attemptedValue}")
    }
    println("fuelType after failed set (unchanged): ${fuel?.value}")

    // Unconstrained string control accepts any value
    val colour = controls.stringControl("Van_2.colour")
    println("\nInitial colour: ${colour?.value}")
    colour?.value = "MIDNIGHT BLUE"
    println("After setting MIDNIGHT BLUE: ${colour?.value}")

    // Bulk-set via map — invalid entry is skipped, valid entries are applied
    // Uses Van_0 (untouched above); Van_1 and Van_2 were used in the individual tests.
    println("\n=== Bulk setStringControlsFromMap ===")
    val updates = mapOf(
        "Van_0.fuelType" to "DIESEL",   // valid
        "Van_0.colour"   to "RED",      // valid (unconstrained)
        "Van_1.fuelType" to "JET_FUEL", // invalid — skipped with warning
    )
    val applied = controls.setStringControlsFromMap(updates)
    println("Applied $applied of ${updates.size} updates")
    println("Van_0.fuelType = ${controls.stringControl("Van_0.fuelType")?.value}")
    println("Van_0.colour   = ${controls.stringControl("Van_0.colour")?.value}")
    println("Van_1.fuelType = ${controls.stringControl("Van_1.fuelType")?.value} (unchanged)")
}

// ── Demo 3: JSON KSLJsonControl extraction ────────────────────────────────────

/**
 * A model element with JSON-valued controls used to demonstrate the
 * `@KSLJsonControl` annotation together with the [ksl.controls.Controls]
 * extraction API.
 *
 * Both properties are serializable by `kotlinx.serialization` without any
 * additional annotations — `List<Double>` and `Map<String, Double>` are
 * handled natively.
 */
class Truck(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLJsonControl(comment = "Gross weight per axle in kg")
    var axleWeights: List<Double> = listOf(4500.0, 6000.0, 6000.0)

    @set:KSLJsonControl(comment = "Named cargo compartment capacities in cubic metres")
    var compartmentCapacities: Map<String, Double> =
        mapOf("front" to 12.5, "rear" to 18.0)
}

/**
 * Demonstrates extraction and mutation of JSON controls.
 *
 * Shows:
 * - Reading initial JSON values and type hints.
 * - Setting a valid JSON value.
 * - Attempting invalid JSON and catching [ControlUpdateException].
 * - Bulk-setting JSON controls via `setJsonControlsFromMap`.
 */
fun demoJsonControls() {
    val model = Model("Truck Model — JSON")
    repeat(3) { Truck(model, "Truck_$it") }
    val controls = model.controls()

    println("=== JSON controls (${controls.jsonControlSize}) ===")
    println(controls.jsonControlDataAsString())

    // Read initial JSON value and type hint
    val axles = controls.jsonControl("Truck_0.axleWeights")
    println("Type hint : ${axles?.typeHint}")
    println("Initial   : ${axles?.initialJsonValue}")
    println("Current   : ${axles?.value}")

    // Valid assignment — replace axle weights with a four-axle configuration
    axles?.value = "[5000.0, 7000.0, 7000.0, 7000.0]"
    println("After update: ${axles?.value}")

    // Invalid assignment — malformed JSON caught and reported
    println("\nAttempting to set axleWeights to invalid JSON:")
    try {
        axles?.value = "[not, valid, json]"
    } catch (e: ControlUpdateException) {
        println("  Caught ControlUpdateException: ${e.message}")
        println("  Control key: ${e.controlKey}")
    }
    println("axleWeights after failed set (unchanged): ${axles?.value}")

    // Bulk-set via map — one valid, one with wrong element type (Double list vs String list)
    println("\n=== Bulk setJsonControlsFromMap ===")
    val updates = mapOf(
        "Truck_1.axleWeights"           to "[3000.0, 5500.0]",              // valid
        "Truck_2.compartmentCapacities" to """{"fwd":10.0,"mid":15.0,"aft":20.0}""", // valid
        "Truck_0.axleWeights"           to """{"bad":"structure"}""",        // wrong type — skipped
    )
    val applied = controls.setJsonControlsFromMap(updates)
    println("Applied $applied of ${updates.size} updates")
    println("Truck_1.axleWeights           = ${controls.jsonControl("Truck_1.axleWeights")?.value}")
    println("Truck_2.compartmentCapacities = ${controls.jsonControl("Truck_2.compartmentCapacities")?.value}")
    println("Truck_0.axleWeights           = ${controls.jsonControl("Truck_0.axleWeights")?.value} (unchanged)")
}

fun main() {
    demoNumericControls()
    println("\n" + "=".repeat(60) + "\n")
    demoStringControls()
    println("\n" + "=".repeat(60) + "\n")
    demoJsonControls()
}
