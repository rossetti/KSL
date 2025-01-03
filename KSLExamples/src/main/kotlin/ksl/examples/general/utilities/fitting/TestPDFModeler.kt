/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.utilities.fitting

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.PearsonType5
import ksl.utilities.distributions.fitting.*
import ksl.utilities.distributions.fitting.estimators.ExponentialMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.PearsonType5MLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.WeibullMLEParameterEstimator
import ksl.utilities.distributions.fitting.scoring.*
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.writeToFile
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.random.rvariable.parameters.GeneralizedBetaRVParameters
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.U01Test
import org.jetbrains.kotlinx.dataframe.api.sortBy
import org.jetbrains.kotlinx.dataframe.api.sortByDesc
import org.jetbrains.letsPlot.commons.intern.math.ipow

fun main() {
    val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = e.sample(n)
//    data.write(KSL.out)
//       testModeler(data)
    //      testExponentialEstimation(data)
    //   testWeibullEstimation(data)

//    testEvaluationModel(data)
//    testAllInOne(data)

//    testGammaCaseV2(7.5, 1.1)

    //   testSampleFile()

//    testAndersonDarling()

    testBootStrappingOfFamily()

//    splitAnalysis(80.0)
}

fun splitAnalysis(splitCriteria: Double){
    // select file: ch6problem4.txt
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null){
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val (bottom, top) = data.partition { it<= splitCriteria }
        val bottomAnalysis = PDFModeler(bottom.toDoubleArray())
        bottomAnalysis.showAllResultsInBrowser()
        val topAnalysis = PDFModeler(top.toDoubleArray())
        topAnalysis.showAllResultsInBrowser()
    }
}

private fun testSampleFile() {
    val myFile = KSLFileUtil.chooseFile()
    if (myFile != null) {
        val data = KSLFileUtil.scanToArray(myFile.toPath())
        val d = PDFModeler(data)
        d.histogram.histogramPlot().showInBrowser()
        val estimationResults = d.estimateParameters(PDFModeler.allEstimators, true)
        val scores = d.evaluateScores(estimationResults)
        val result = d.estimateAndEvaluateScores()
        d.showAllResultsInBrowser()
    }
}

private fun testModeler(data: DoubleArray) {
    val d = PDFModeler(data)
    val list = d.estimateParameters(PDFModeler.allEstimators)
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

    val result = ExponentialMLEParameterEstimator.estimateParameters(data)

    println("Results for ${result.distribution}")

    println()

    val d = PDFModeler.createDistribution(result.parameters!!)!!
    println(d)
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
    val estimationResults: List<EstimationResult> = d.estimateParameters(PDFModeler.allEstimators)
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
    results.resultsSortedByScoring.forEach(::println)

    val topResult = results.resultsSortedByScoring[0]
    topResult.distributionFitPlot().showInBrowser("Recommended Distribution ${topResult.name}")

    val secondResult = results.resultsSortedByScoring[1]
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
                s.collect(result.resultsSortedByScoring[0].rvType == RVType.Gamma)
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

fun testGammaCaseV2(shape: Double, scale: Double) {

    // take the fifth option
    val rv = GammaRV(shape, scale)
    val familyStats = mutableMapOf<Int, Statistic>()
    val shapeStats = mutableMapOf<Int, Statistic>()
    val scaleStats = mutableMapOf<Int, Statistic>()
    val bothStats = mutableMapOf<Int, Statistic>()
    for(i in 1..9){
        val ss = (2).ipow(i + 2).toInt()
        familyStats[ss] = Statistic("SampleSizeStats_$ss")
        shapeStats[ss] = Statistic("ShapeStats_$ss")
        scaleStats[ss] = Statistic("ScaleStats_$ss")
        bothStats[ss] = Statistic("BothStats_$ss")
    }
    for ((i, s) in familyStats) {
        println("Working on sample size $i")
        for (j in 1..1000) {
            val data = rv.sample(i)
            try {
                val d = PDFModeler(data)
                val result: PDFModelingResults = d.estimateAndEvaluateScores(automaticShifting = false)
                val firstScoringResult = result.resultsSortedByScoring.first()
                if (firstScoringResult.rvType == RVType.Gamma){
                    s.collect(1.0)
                    val er = firstScoringResult.estimationResult
                    val bootStrapParameterEstimates = d.bootStrapParameterEstimates(er)
                    val shapeInterval = bootStrapParameterEstimates[0].percentileBootstrapCI()
                    val scaleInterval = bootStrapParameterEstimates[1].percentileBootstrapCI()
                    shapeStats[i]!!.collect(shapeInterval.contains(shape))
                    scaleStats[i]!!.collect(scaleInterval.contains(scale))
                    bothStats[i]!!.collect(shapeInterval.contains(shape) && scaleInterval.contains(scale))
                } else {
                    s.collect(0.0)
                }
            } catch (e: IllegalArgumentException) {
                data.writeToFile("ErrorData_n_${i}_sample_${j}.txt")
                throw e
            }
        }
    }
    val statList = mutableListOf<StatisticIfc>()
    statList.addAll(familyStats.values)
    statList.addAll(shapeStats.values)
    statList.addAll(scaleStats.values)
    statList.addAll(bothStats.values)
    val r = StatisticReporter(statList)

    println(r.halfWidthSummaryReport())

    println("Done!")

}

fun testBootStrappingOfFamily(){
    //     val rv = ShiftedRV(5.0, LognormalRV(20.0, 2.0))
//    val rv = LognormalRV(20.0, 2.0)
//    val rv = ExponentialRV(mean = 10.0)
    val rv = UniformRV(5.0, 15.0 )
    //   val rv = TriangularRV(3.0, 6.0, 10.0)
    val data = rv.sample(100)

    PDFScoringModel.DEFAULT_NUM_PARAMETER_OPTION = true
    val sm = setOf(
        BayesianInfoCriterionScoringModel(),
        AndersonDarlingScoringModel(),
        CramerVonMisesScoringModel(),
        QQCorrelationScoringModel(),
//        ParameterMSEModel()
    )
    val pdfModeler = PDFModeler(data, sm)
//   PDFModeler.defaultScalingFunction = PDFModeler.Companion.DefaultScalingFunction.Logistic

    pdfModeler.showAllResultsInBrowser()

    val freq = PDFModeler.bootstrapFamilyFrequencyAsDataFrame(data, EvaluationMethod.Scoring,
        automaticShifting = true)
//    val cells = freq.cellsSortedByCount()
//    for(cell in cells){
//        println(cell)
//    }
    println(freq)
}

fun testPearsonType5(){

    val d = PearsonType5(shape = 0.40677819273863525, scale = 2.816753701768571)
     val rv = d.randomVariable

 //   val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = rv.sample(n)
    println(Statistic(data))
    println()

    val pe = PearsonType5MLEParameterEstimator()
    val result = pe.estimateParameters(data, Statistic(data))
    println(result)
    // val sm = ChiSquaredScoringModel()
    val cdf = PDFModeler.createDistribution(result.parameters!!)
    println(cdf)

    val p = U01Test.recommendedU01BreakPoints(1000, PDFModeler.defaultConfidenceLevel)
    println()
    println(p.joinToString())

    var bp = PDFModeler.equalizedCDFBreakPoints(data.size, cdf!!)

    println()
    println(bp.joinToString())

    val sm = AndersonDarlingScoringModel()

  //  sm.score(data, result.parameters!!)
}