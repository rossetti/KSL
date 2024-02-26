/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.utilities

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.fitting.*
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.writeToFile
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.GeneralizedBetaRVParameters
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic
import org.jetbrains.letsPlot.commons.intern.math.ipow

fun main() {
    val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = e.sample(n)
//    data.write(KSL.out)
    //   testModeler(data)
    //      testExponentialEstimation(data)
    //   testWeibullEstimation(data)

//    testEvaluationModel(data)
//    testAllInOne(data)

    testGammaCase(7.5, 1.1)
    //   testSampleFile()

//    testAndersonDarling()
}

private fun testSampleFile() {
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null) {
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        d.histogram.histogramPlot().showInBrowser()
        val estimationResults = d.estimateParameters(d.allEstimators, true)
        val scores = d.evaluateScores(estimationResults, d.allScoringModels)
        val result = d.estimateAndEvaluateScores()
        d.showAllResultsInBrowser()
    }
}

private fun testModeler(data: DoubleArray) {
    val d = PDFModeler(data)
    val list = d.estimateParameters(d.allEstimators)
    val scoreResults = d.scoringResults(list)

    for (element in list) {
        println(element.toString())
    }

    println()
    for (result in scoreResults) {
        println("Distribution = ${result.name}")
        for (score in result.scores) {
            println("\t ${score.metric.name} = ${score.value}")
        }
        println()
    }

}

private fun testExponentialEstimation(data: DoubleArray) {
    val estimator = ExponentialMLEParameterEstimator

    val result = ExponentialMLEParameterEstimator.estimateParameters(data)

    println("Results for ${result.distribution}")

    println()

    val d = PDFModeler.createDistribution(result.parameters!!)!!
    println(d)
    val params = result.parameters
    val mean = params?.doubleParameter("mean")
    //val d = Exponential(mean)
    var bp = PDFModeler.equalizedCDFBreakPoints(data.size, d)
    bp = Histogram.addLowerLimit(0.0, bp)
    bp = Histogram.addPositiveInfinity(bp)
    val h = Histogram(bp)
    h.collect(data)
//    println(h)

    val ec = PDFModeler.expectedCounts(h, d)
    println("number of counts = ${ec.size}")
    println("number of bins = ${h.numberBins}")
//    println(ec.joinToString())

    val chiSq = Statistic.chiSqTestStatistic(h.binCounts, ec)
    println("Chi-squared test statistic = $chiSq")
    val dof = h.numberBins - 1 - 1
    val chiDist = ChiSquaredDistribution(dof.toDouble())
    val pValue = chiDist.complementaryCDF(chiSq)
    println("P-Value = $pValue")

    // test the scoring
    //val models = setOf(ChiSquaredScoringModel)
    val score = ChiSquaredScoringModel().score(result)
    println(score)
}

private fun testWeibullEstimation(data: DoubleArray) {
    val estimator = WeibullMLEParameterEstimator()
    //   val estimator = WeibullPercentileParameterEstimator()
    val result = estimator.estimateParameters(data)

    val d = PDFModeler.createDistribution(result.parameters!!)!!
    println(d)
    println()
    val params = result.parameters

    var bp = PDFModeler.equalizedCDFBreakPoints(data.size, d)
    // println(bp.joinToString())
    println()
    bp = Histogram.addLowerLimit(0.0, bp)
    bp = Histogram.addPositiveInfinity(bp)
    val h = Histogram(bp)
    h.collect(data)
    println(h)

    val ec = PDFModeler.expectedCounts(h, d)
    println("number of counts = ${ec.size}")
    println("number of bins = ${h.numberBins}")
    println("expected count = ${ec[0]}")
    // println(ec.joinToString())

    val chiSq = Statistic.chiSqTestStatistic(h.binCounts, ec)
    println("Chi-squared test statistic = $chiSq")
    val dof = h.numberBins - 2 - 1
    val chiDist = ChiSquaredDistribution(dof.toDouble())
    val pValue = chiDist.complementaryCDF(chiSq)
    println("P-Value = $pValue")

    // test the scoring
    //val models = setOf(ChiSquaredScoringModel)
    val score = ChiSquaredScoringModel().score(result)
    println(score)
}

/*
# Cramer-von Mises test of goodness-of-fit
# Null hypothesis: Weibull distribution
# with parameters shape = 0.99983358911843, scale = 10.1890749114815
# Parameters assumed to be fixed
#
# data:  y
# omega2 = 0.055559, p-value = 0.842
 */

fun testEvaluationModel(data: DoubleArray) {
    val d = PDFModeler(data)
    val estimationResults: List<EstimationResult> = d.estimateParameters(d.allEstimators)
    val scoringResults = d.scoringResults(estimationResults)
    val model = d.evaluateScoringResults(scoringResults)
    scoringResults.forEach(::println)

    println()
    println(model.alternativeValuesAsDataFrame("Distributions"))
}

fun testAllInOne(data: DoubleArray) {
    println()
    val d = PDFModeler(data)
    val results = d.estimateAndEvaluateScores()
    results.sortedScoringResults.forEach(::println)

    val topResult = results.sortedScoringResults[0]
    topResult.distributionFitPlot().showInBrowser("Recommended Distribution ${topResult.name}")

    val secondResult = results.sortedScoringResults[1]
    secondResult.distributionFitPlot().showInBrowser("Second Distribution ${secondResult.name}")
    val gResult = secondResult.estimationResult

    println()
    val bsr = d.bootStrapParameterEstimates(gResult)
    bsr.forEach(::println)
}

fun testGammaCase(shape: Double, scale: Double) {

    // take the fifth option
    val rv = GammaRV(shape, scale)
    val experiments = mutableMapOf<Int, Statistic>()
    for(i in 1..9){
        val ss = (2).ipow(i + 2).toInt()
        experiments[ss] = Statistic("SampleSizeStats_$ss")
    }
    for ((i, s) in experiments) {
        println("Working on sample size $i")
        for (j in 1..1000) {
            val data = rv.sample(i)
            try {
                val d = PDFModeler(data)
                val result: PDFModelingResults = d.estimateAndEvaluateScores(automaticShifting = false)
                val er: EstimationResult = result.sortedScoringResults[0].estimationResult
                val something = d.bootStrapParameterEstimates(er)
                val interval = something[0].percentileBootstrapCI()
                val p = result.sortedScoringResults[0].estimationResult.parameters
                val beta = p!!.doubleParameter("scale")
                val alpha = p!!.doubleParameter("shape")
                s.collect(result.sortedScoringResults[0].rvType == RVType.Gamma)
            } catch (e: IllegalArgumentException) {
                data.writeToFile("ErrorData_n_${i}_sample_${j}.txt")
                throw e
            }
        }
    }
    val r = StatisticReporter(experiments.values.toMutableList())
    println(r.halfWidthSummaryReport())

    println("Done!")

}

fun testAndersonDarling() {
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null) {
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val parameters = GeneralizedBetaRVParameters()
        parameters.changeDoubleParameter("alpha", 2.2714476680290643)
        parameters.changeDoubleParameter("beta", 8.3184667381217)
        parameters.changeDoubleParameter("min", 2.351646861220419)
        parameters.changeDoubleParameter("max", 31.582890580323173)
        val cdf = parameters.createDistribution()
        val score = Statistic.andersonDarlingTestStatistic(data, cdf)
        println("score = $score")
    }
}