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

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic


fun main() {
    val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = e.sample(n)
//    data.write(KSL.out)
    testModeler(data)
 //      testExponentialEstimation(data)
    //   testWeibullEstimation(data)
}

private fun testModeler(data: DoubleArray) {
    val d = PDFModeler(data)
    val list = d.estimateParameters(d.allEstimators)
    d.scoreResults(list)
    for (element in list) {
        println(element.toString())
    }
}

private fun testExponentialEstimation(data: DoubleArray) {
    val estimator = ExponentialMLEParameterEstimator

    val result = estimator.estimate(data)

    println("Results for ${result.distribution}")

    println()

    val d = PDFModeler.createDistribution(result.parameters!!)!!
    println(d)
    val params = result.parameters
    val mean = params.doubleParameter("mean")
    //val d = Exponential(mean)
    var bp = PDFModeler.equalizedCDFBreakPoints(data.size, d)
    bp = Histogram.addLowerLimit(0.0, bp)
    bp = Histogram.addPositiveInfinity(bp)
    val h = Histogram(bp)
    h.collect(data)
//    println(h)

    val ec = h.expectedCounts(d)
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
    val score = ChiSquaredScoringModel.score(result)
    println(score)
}

private fun testWeibullEstimation(data: DoubleArray) {
    val estimator = WeibullMLEParameterEstimator()
    //   val estimator = WeibullPercentileParameterEstimator()
    val result = estimator.estimate(data)

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

    val ec = h.expectedCounts(d)
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
    val score = ChiSquaredScoringModel.score(result)
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