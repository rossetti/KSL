package ksl.controls.testing

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * "Van" class with various vehicle attributes to Test annotationControl
 * - extraction from code (via reflection),
 * - put and get of control values
 */
class Van(parent: ModelElement) : ModelElement(parent) {

    // numeric control setter with bounds, an alias and a comment
    @set:KSLControl(
        controlType = ControlType.INTEGER,
        name = "numberOfSeats",
        lowerBound = 1.0,
        upperBound = 18.0,
        comment = "0 seats == autonomous driving ?"
    )
    var numSeats = 3
        set(n) {
            require(n >= 1) { "The number of seats must be >= 1" }
            field = n
        }

    // numeric control setter with bounds
    @set:KSLControl(controlType = ControlType.SHORT, lowerBound = 3.0, upperBound = 8.0)
    var numWheels: Short = 4

    // numeric control setter with all defaults
    @set:KSLControl(controlType = ControlType.DOUBLE)
    var price = 1.2345

    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var isStickShift = true

}

fun main() {
    val model = Model("Van Model")

    // make a few Vans (and add them to Model)
    val k = 6
    val vans = arrayOfNulls<Van>(k)
    for (i in 0 until k) {
        vans[i] = Van(model)
    }

    val controls = model.controls()

    //   println(controls.controlRecordsAsString())
    val c = controls.control("Van_3.numSeats")
    c?.value = 100.0
    println(c)

    val c2 = controls.control("Van_8.isStickShift")
    c2?.value = 0.0
    println(c2)

    println()
    println(controls.controlsMapAsJsonString())
}