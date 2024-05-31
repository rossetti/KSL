package ksl.examples.book.chapter5

import ksl.simulation.Model

/**
 *  Example 5.8
 *  Illustrate the basic use of controls.
 */
fun main() {
    val model = Model("Pallet Model MCB")
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model, name ="PWC")
    println("Original value of property:")
    println("num workers = ${palletWorkCenter.numWorkers}")
    println()
    val controls =  model.controls()
    println("Control keys:")
    for (controlKey in controls.controlKeys()) {
        println(controlKey)
    }
    // find the control with the desired key
    val control = controls.control("PWC.numWorkers")!!
    // set the value of the control
    control.value = 3.0
    println()
    println("Current value of property:")
    println("num workers = ${palletWorkCenter.numWorkers}")
}