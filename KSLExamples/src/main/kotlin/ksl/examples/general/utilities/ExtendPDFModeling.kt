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

package ksl.examples.general.utilities

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults
import ksl.utilities.distributions.fitting.ScoringResult
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.dbutil.TabularData
import ksl.utilities.io.tabularfiles.TabularOutputFile
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.*
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.BootstrapEstimate
import ksl.utilities.statistic.BootstrapSampler
import java.nio.file.Path

/**
 *  A rank of 0, means that the random variable type [rvType] was not
 *  found in the results. The returned rank {1, 2, ...} is dependent on
 *  the number of distributions that were fit within the PDF modeling
 *  process. A rank of 1, means that the distribution was the recommended
 *  distribution (top ranked) based on the scoring model. Thus, lower
 *  ranks imply better distribution fit.  The returned rank is the first
 *  instance of the random variable type.
 */
fun PDFModelingResults.rank(rvType: RVType): Int {
    // get the ranked results, save to do sort only once
    val rankedResults = resultsSortedByScoring
    // find the result for the specified random variable type
    val scoringResult: ScoringResult? = rankedResults.find { it.rvType == rvType }
    return if (scoringResult == null) {
        0
    } else {
        rankedResults.indexOf(scoringResult) + 1
    }
}

/**
 *  The PDF modeling process estimates the parameters for a set of
 *  parameter estimators.  Each estimator is unique in the set of estimators
 *  that are processed. Each estimator produces scores on its quality of fit
 *  for the distribution. The scores are combined into an overall score for the
 *  estimator.
 *
 *  The key thing to remember is that different estimators can be supplied
 *  to estimate the parameters from the same distribution family. This is because
 *  there can be many different algorithms to estimate the parameters associated with
 *  some distribution. For example, the gamma distribution's parameters can be
 *  estimated using a method of moments algorithm or a maximum likelihood estimation
 *  algorithm. Thus, the scoring results are about the estimators, not about specific
 *  distributions unless the set of evaluated parameter estimators do not have multiple
 *  algorithms for the same distribution.
 *
 *  Therefore, the ranking of the scoring model is about the estimator used.
 *
 *  A rank of 0, means that the estimation result [estimationResult] was not
 *  found in the results. The returned rank {1, 2, ...} is dependent on
 *  the number of estimators that were fit within the PDF modeling
 *  process. A rank of 1, means that the estimator associated
 *  with the estimation result was the recommended
 *  estimator (top ranked) based on the scoring model. Thus, lower
 *  ranks imply better estimation fit.
 */
fun PDFModelingResults.rank(estimationResult: EstimationResult): Int {
    // get the ranked results, save to do sort only once
    val rankedResults = resultsSortedByScoring
    // find the result for the specified estimation result
    val scoringResult: ScoringResult? = rankedResults.find { it.estimationResult == estimationResult }
    return if (scoringResult == null) {
        0
    } else {
        rankedResults.indexOf(scoringResult) + 1
    }
}

/**
 *  Returns a map containing the double and integer valued
 *  parameters. The key to the map is the name of the parameter
 *  and the value is the current value of the parameter. If the
 *  parameter is integer value, it is converted to a double value.
 */
fun RVParameters.asDoubleMap(): Map<String, Double> {
    val map = mutableMapOf<String, Double>()
    for (k in this.doubleParameterNames) {
        map[k] = this.doubleParameter(k)
    }
    for (k in this.integerParameterNames) {
        map[k] = this.integerParameter(k).toDouble()
    }
    return map
}

/**
 *  Returns a map containing the double and integer valued
 *  parameters from the estimation result.
 *
 *  The key to the map is the name of the parameter
 *  and the value is the current estimated value of the parameter. If the
 *  parameter is integer value, it is converted to a double value.
 *
 *  The map may be empty if the underlying parameters for the
 *  estimation result was null.
 */
fun EstimationResult.parameters(): Map<String, Double> {
    return if (this.parameters == null) {
        mapOf()
    } else {
        parameters!!.asDoubleMap()
    }
}

/**
 *  Returns a map containing the percentile bootstrap confidence
 *  intervals for the parameters associated with the estimation result.
 *
 *  The key to the map is the name of the parameter as specified
 *  by the estimator associated with the estimation result
 *  and the value is an interval representing the percentile bootstrap
 *  confidence interval.
 *
 *  @param numBootstrapSamples the number of bootstrap samples
 *  @param level the desired confidence interval level for each parameter
 *  @param stream the stream for the bootstrap sampling
 *  @return a map with key = parameter name and value being the interval
 *
 */
fun EstimationResult.percentileBootstrapCI(
    numBootstrapSamples: Int = 399,
    level: Double = 0.95,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
): Map<String, Interval> {
    val map = mutableMapOf<String, Interval>()
    val bMap = bootstrapParameters(numBootstrapSamples, stream)
    for ((name, e) in bMap) {
        map[name] = e.percentileBootstrapCI(level)
    }
    return map
}

/**
 *  Performs the bootstrap sampling of the parameters associated
 *  with the estimation result.
 *
 *  @param numBootstrapSamples the number of bootstrap samples
 *  @param stream the stream for the bootstrap sampling
 *  @return map of BootStrapEstimate instances representing the bootstrap
 *  estimate results for each parameter. The key to the map is the name
 *  of the parameter.
 */
fun EstimationResult.bootstrapParameters(
    numBootstrapSamples: Int = 399,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
): Map<String, BootstrapEstimate> {
    val data = if (shiftedData != null) {
        shiftedData!!.shiftedData
    } else {
        originalData
    }
    val bss = BootstrapSampler(data, estimator, stream)
    val list = bss.bootStrapEstimates(numBootstrapSamples)
    val map = mutableMapOf<String, BootstrapEstimate>()
    for (e in list) {
        map[e.name] = e
    }
    return map
}

fun evaluation(
    rvType: RVType,
    rv: ParameterizedRV,
    sampleSize: Int,
    automaticShifting: Boolean = false
) {
    val parameters = rv.parameters.asDoubleMap()
    val data = rv.sample(sampleSize)
    val pdfModeler = PDFModeler(data)
    val pdfModelingResults = pdfModeler.estimateAndEvaluateScores(automaticShifting = automaticShifting)
    // still need to make sure that the top performer was of the correct type
    val er: EstimationResult = pdfModelingResults.resultsSortedByScoring.first().estimationResult
    val estimatedParameters = er.parameters()
    val parameterCI = er.percentileBootstrapCI()
    // now the closeness of the estimated parameters to the true values can be checked
    // now the CI can be checked to see if they contain the true values
}


data class PDFModelingData(
    var sampleSize: Int = -1,
    var distribution: String = "",
    var trueParam1: Double = Double.NaN,
    var trueParam2: Double = Double.NaN,
    var estParam1: Double = Double.NaN,
    var estParam2: Double = Double.NaN,
    var rank: Int = -1,
    var firstPlace: String = "",
    var secondPlace: String = "",
    var thirdPlace: String = "",
    var sampleID: Int = -1,
    var average: Double = Double.NaN,
    var stdDev: Double = Double.NaN,
    var min: Double = Double.NaN,
    var max: Double = Double.NaN,
    var skewness: Double = Double.NaN,
    var kurtosis: Double = Double.NaN,
    var ksScore: Double = Double.NaN,
    var sqeScore: Double = Double.NaN,
    var chiSqScore: Double = Double.NaN,
    var adScore: Double = Double.NaN,
    var cvmScore: Double = Double.NaN,
) : TabularData("PDFModelingData")

class PDFModelingExperiment(
    name: String,
    dirPath: Path = KSL.outDir
) {
    private val tFile: TabularOutputFile

    init {
        val path: Path = dirPath.resolve(name)
        val rowData = PDFModelingData()
        tFile = TabularOutputFile(rowData, path)
    }

    fun runExperiment(
        distribution: String,
        trueParam1: Double,
        trueParam2: Double,
        rv: ParameterizedRV,
        sampleSize: Int,
        numSamples: Int,
        automaticShifting: Boolean = false
    ) {
        require(sampleSize >= 2) { "The size of each sample must be >= 2" }
        require(numSamples >= 2) { "The number of samples to generate must be >= 2" }
        for (i in 1..numSamples) {
            val data = rv.sample(sampleSize)
            val pdfModeler = PDFModeler(data)
            val pdfModelingResults = pdfModeler.estimateAndEvaluateScores(automaticShifting = automaticShifting)
            saveResults(distribution, trueParam1, trueParam2, pdfModelingResults)
        }
    }

    private fun saveResults(
        distribution: String,
        trueParam1: Double,
        trueParam2: Double,
        pdfModelingResults: PDFModelingResults
    ) {
        val rowData = PDFModelingData()
        rowData.distribution = distribution
        rowData.trueParam1 = trueParam1
        rowData.trueParam2 = trueParam2
        val firstResult = pdfModelingResults.resultsSortedByScoring[0]
        val secondResult = pdfModelingResults.resultsSortedByScoring[1]
        val thirdResult = pdfModelingResults.resultsSortedByScoring[2]
        pdfModelingResults.estimationResults[0]

    }

}
