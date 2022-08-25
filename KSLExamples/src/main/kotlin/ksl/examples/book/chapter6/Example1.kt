package ksl.examples.book.chapter6

import ksl.simulation.Model

/**
 * This example illustrates how to create a simulation model,
 * attach a new model element, set the run length, and
 * run the simulation. The example use the SchedulingEventExamples
 * class to show how actions are used to implement events.
 */
fun main() {
    val m = Model("Scheduling Example")
    SchedulingEventExamples(m.model)
    m.lengthOfReplication = 100.0
//    m.autoPrintSummaryReport = false
    m.simulate()
}
