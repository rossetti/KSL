package ksl.examples.book.chapter7

import ksl.simulation.Model

fun main() {
//    baseCase()
//    infiniteCapacityCase()
    scheduledCase()
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

fun scheduledCase(){
    val m = Model()
    val mixer = StemFairMixerEnhancedSched(m, "Stem Fair Scheduled")
    mixer.warningTime = 30.0
//    m.lengthOfReplication = 360.0
    m.numberOfReplications = 400
    m.simulate()
    m.print()
}

