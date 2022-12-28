package ksl.examples.book.chapter7

import ksl.simulation.Model

fun main() {
//    baseCase()
    infiniteCapacityCase()
}

fun baseCase() {
    val m = Model()
    StemFairMixerEnhanced(m, "Stem Fair Base Case")
    m.numberOfReplications = 400
    m.simulate()
    m.print()
}

fun infiniteCapacityCase() {
    val m = Model()
    val mixer = StemFairMixerEnhanced(m, "Stem Fair Infinite")
    mixer.JHBuntRecruiters.initialCapacity = Int.MAX_VALUE
    mixer.MalWartRecruiters.initialCapacity = Int.MAX_VALUE
//    mixer.warningTime = 30.0
    m.numberOfReplications = 400
    m.simulate()
    m.print()
}

