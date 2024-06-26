package ksl.examples.book.chapter5

import ksl.simulation.Model
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 *  Example 5.9
 *  Illustrate the basic use of the RVParameterSetter class.
 */
fun main() {
    val model = Model("Pallet Model MCB")
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model, name ="PWC")
    println(palletWorkCenter.processingTimeRV)
    val tmpSetter = RVParameterSetter(model)
    val map = tmpSetter.rvParameters
    println()
    println("Standard Map Representation:")
    for ((key, value) in map) {
        println("$key -> $value")
    }
    val rv  = map["ProcessingTimeRV"]!!
    rv.changeDoubleParameter("mode", 14.0)
    tmpSetter.applyParameterChanges(model)
    println()
    println(palletWorkCenter.processingTimeRV)
    println()
    val flatMap = tmpSetter.flatParametersAsDoubles
    println("Flat Map Representation:")
    for ((key, value) in flatMap) {
        println("$key -> $value")
    }
}