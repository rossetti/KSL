package ksl.controls.testing

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * "Van" class with various vehicle attributes to Test annotationControl
 * - extraction from code (via relfection),
 * - put and get of control values
 */
class Van(parent: ModelElement) : ModelElement(parent) {

    private var stickShift = false

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
            field = n ?: numSeats
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

fun main(){
    val model = Model("Van Model")

    // make a few Vans (and add them to Model)
    val k = 6
    val vans = arrayOfNulls<Van>(k)
    for (i in 0 until k) {
        vans[i] = Van(model)
    }

    println("ModelElements")
//    println(model.modelElementsAsString)

    val controls = model.controls()

    println(controls.controlRecordsAsString())

}