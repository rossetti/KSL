package ksl.examples.book.chapter4

import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.StateFrequency

/**
 * This example illustrates how to define labeled states
 * and to tabulate observations of those states. The StateFrequency
 * class generalizes the IntegerFrequency class by allowing the user
 * to collect observations on labeled states rather than integers.
 * This also allows for the tabulation of counts and proportions of single
 * step transitions between states.
 */
fun main() {
    val sf = StateFrequency(6)
    val states = sf.states
    for (i in 1..10000) {
        val state = KSLRandom.randomlySelect(states)
        sf.collect(state)
    }
    println(sf)
}
