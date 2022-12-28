package ksl.examples.book.chapter7

import ksl.simulation.Model

fun main(){
    val m = Model()
    WalkInHealthClinic(m, "Walk-In Clinic")
    m.lengthOfReplication = 10.0 * 60.0
    m.numberOfReplications = 30
    m.simulate()
    m.print()
}