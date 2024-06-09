package ksl.examples.general.utilities

import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rvariable.ExponentialRV

fun main(){
    val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = e.sample(n)
    val d = PDFModeler(data)
    val estimationResults: List<EstimationResult> = d.estimateParameters(PDFModeler.allEstimators)
    val scoringResults = d.scoringResults(estimationResults)
    val model = d.evaluateScoringResults(scoringResults)
//    scoringResults.forEach(::println)

//    println()
//    println(model.alternativeValuesAsDataFrame("Distributions"))
//    println()
//    println(model.alternativeScoresAsDataFrame("Distributions"))
//    println()
//    println(model.alternativeRanksAsDataFrame("Distributions"))
//    println()
//    val list = model.alternativeScoreData()
//    for(sd in list) {
//        println(sd)
//    }
//    println()
//    val stuff = model.alternativeValueData()
//    for(vd in stuff) {
//        println(vd)
//    }
//    println()
//    val aValues = model.alternativeOverallValueData()
//    for(vd in aValues) {
//        println(vd)
//    }

    val db = model.resultsAsDatabase("Results")

    val sortedAlternatives = model.sortedMultiObjectiveValuesByAlternative()
    for(pair in sortedAlternatives) {
        println("${pair.first} , value = ${pair.second}")
    }
    println()
    println("Top alternatives based on multi-objective value:")
    val topByValue = model.topAlternativesByMultiObjectiveValue()
    println(topByValue)

//    println()
//    val altFreq = model.alternativeFirstRankMetricFrequencies()
//    for((alt, freq) in altFreq) {
//        println(freq)
//    }
    println()
    val altRankCounts = model.alternativeFirstRankCounts()
    for((alt, freq) in altRankCounts) {
        println("alt = ${alt}, first rank count = $freq")
    }

    println()
    println("Top alternatives based on metric first ranking counts:")
    val topByRank = model.topAlternativesByFirstRankCounts()
    println(topByRank)
}