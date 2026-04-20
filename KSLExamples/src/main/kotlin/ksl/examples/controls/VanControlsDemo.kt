package ksl.examples.controls

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * A simple model element with numeric and boolean controls used to demonstrate
 * the `@KSLControl` annotation and the [ksl.controls.Controls] extraction API.
 *
 * Each annotated property becomes a controllable parameter that can be read and
 * written via [ksl.controls.Controls] without touching the class directly.
 */
class Van(parent: ModelElement) : ModelElement(parent) {

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
}

// ── Demo: numeric and boolean KSLControl extraction ──────────────────────────

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
    val model = Model("Van Model")

    val numVans = 5
    repeat(numVans) { Van(model) }

    val controls = model.controls()

    println("=== Extracted controls (${controls.size}) ===")
    println(controls.controlDataAsString())

    // Read and write a numeric control — value 100 is clamped to upperBound 18
    val seatsControl = controls.control("Van_1.numSeats")
    println("Before: $seatsControl")
    seatsControl?.value = 100.0
    println("After setting 100 (clamped to upper bound 18): $seatsControl")

    // Toggle a boolean control via the 1.0/0.0 convention
    val shiftControl = controls.control("Van_2.isStickShift")
    println("\nBefore: $shiftControl")
    shiftControl?.value = 0.0
    println("After setting 0.0 (false): $shiftControl")

    // Print the full flat map as JSON
    println("\n=== Controls map (JSON) ===")
    println(controls.controlsMapAsJsonString())
}

fun main() {
    demoNumericControls()
}
