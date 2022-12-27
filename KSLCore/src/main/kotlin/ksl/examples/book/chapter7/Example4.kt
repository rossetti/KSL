package ksl.examples.book.chapter7

import ksl.simulation.Model

fun main() {
    val m = Model()
    StemFairMixerEnhanced(m, "Stem Fair Enhanced")
    m.lengthOfReplication = 6.0 * 60.0
    m.numberOfReplications = 400
    m.simulate()
    m.print()
}