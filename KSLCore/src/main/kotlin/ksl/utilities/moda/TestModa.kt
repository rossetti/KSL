package ksl.utilities.moda

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.sortBy

fun main(){
    val e = ExponentialRV(10.0)
    val data = e.sample(1000)
    val d = PDFModeler(data)
    val pdfModelerResults = d.estimateAndEvaluateScores()
    val model = pdfModelerResults.evaluationModel
    val scoreDf = model.alternativeScoresAsDataFrame("Distributions")
    println(scoreDf)
    println()
    val valueDf = model.alternativeValuesAsDataFrame("Distributions")
    println(valueDf)
    println()
//    val tvCol = valueDf["Total Value"]
//    println(valueDf.sortBy{ tvCol.desc() })

    val cols = intArrayOf(1,2,3,4,5)
    val map = MODAModel.readDataFrame(0, cols, scoreDf )
    println(map)
}