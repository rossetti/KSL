package ksl.examples.general.lectures.week2

import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.ScoringResult
import ksl.utilities.io.KSLFileUtil

fun main(){
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val modeler = PDFModeler(data)
        modeler.showAllResultsInBrowser()
        // how can we test a particular result
        placeResults(modeler, 2)
    }
}

/**
 *  Analyze the distribution based on its placement in the scoring
 *  place = 1 means first place (the default)
 *  place = 2 means second place, et.
 */
fun placeResults(modeler: PDFModeler, place: Int = 1){
    val results  = modeler.estimateAndEvaluateScores()
    val sortedResults: List<ScoringResult> = results.resultsSortedByScoring
    val result: ScoringResult = sortedResults[place-1]
    val distPlot = result.distributionFitPlot()
    distPlot.showInBrowser("The $place Distribution ${result.name}")
    println()
    println("** The $place Distribution** ${result.name}")
    println()
    val gof = ContinuousCDFGoodnessOfFit(result.estimationResult.testData,
        result.distribution,
        numEstimatedParameters = result.numberOfParameters
    )
    println(gof)

}