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

import ksl.utilities.Interval
import ksl.utilities.distributions.*
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic


/**
 *  The purpose of this class is to automate the chi-squared goodness of fit test
 *  for a supplied cumulative distribution function.
 */
class ChiSquaredCDFTest(
    data: DoubleArray,
    df: DistributionFunctionIfc,
) {

    private val dist = df


    //TODO constructor for discrete, for continuous?
    // automatically determine the break points, but allow them to be changed
    // maybe an interface with two implementations for discrete and continuous
}

class ChiSquaredTestPMF(
    private val data: DoubleArray,
    val df: DiscreteDistributionIfc,
    val numEstimatedParameters:  Int,
    breakPoints: DoubleArray? = null,
    val range: Interval = Interval(0.0, Double.POSITIVE_INFINITY)
){
    init {
        TODO("needs fixing")
        require(numEstimatedParameters >= 0) {"The number of estimated parameters must be >= 0"}
    }
    // cannot check if data is in domain of distribution
    private val myBreakPoints: DoubleArray
    private val histogram: Histogram

    init {
        myBreakPoints = if (breakPoints == null){
            var bp = PMFModeler.equalizedCDFBreakPoints(data.size, df)
            bp = Histogram.addLowerLimit(range.lowerLimit, bp)
            Histogram.addUpperLimit(range.upperLimit, bp)
        } else {
            breakPoints.copyOf()
        }
        histogram = Histogram(myBreakPoints)
        histogram.collect(data)
  //      println(histogram)
        TODO("needs fixing")
//        for(bin in histogram.bins){
//            val p = df.cdf(bin)
//            val u = bin.upperLimit
//            val l = bin.lowerLimit
//            val p1 = df.cdf(u) - df.cdf(l)
//            println("$bin   p(bin) = $p      p1 = $p1     l = $l, u = $u")
//        }
    }

    val breakPoints = myBreakPoints.copyOf()

//    val expectedCounts = histogram.expectedCounts(df)

    val binCounts = histogram.binCounts

    val dof = histogram.numberBins - 1 - numEstimatedParameters

//    val chiSquaredTestStatistic = Statistic.chiSqTestStatistic(binCounts, expectedCounts)

    val pValue : Double

    init {
        val chiDist = ChiSquaredDistribution(dof.toDouble())
//        pValue = chiDist.complementaryCDF(chiSquaredTestStatistic)
    }

    override fun toString(): String {
        TODO("needs fixing")
//        val sb = StringBuilder()
//        sb.appendLine("Chi-Squared Test")
//        sb.appendLine()
//        sb.append(String.format("%3s %-12s %-5s %-5s", "Bin", "Range", "Observed", "Expected"))
//        sb.appendLine()
//        for ((i, bin) in histogram.bins.withIndex()) {
//            val s = String.format("%s %f %n", bin, expectedCounts[i])
//            sb.append(s)
//        }
//        sb.appendLine()
//        sb.appendLine("Number of estimate parameters = $numEstimatedParameters")
//        sb.appendLine("Number of intervals = ${histogram.numberBins}")
//        sb.appendLine("Degrees of Freedom = $dof")
//        sb.appendLine("Chi-Squared Test Statistic = $chiSquaredTestStatistic")
//        sb.appendLine("P-value = $pValue")
//        return sb.toString()
    }
}

class ChiSquaredTestPDF(
    private val data: DoubleArray,
    private val df: ContinuousDistributionIfc
){
    // can check if data is in domain of distribution
}

fun main(){
    val dist = Poisson(5.0)

    for (i in 0..10){
        val p = dist.cdf(i) - dist.cdf(i-1)
        println("i = $i  p(i) = ${dist.pmf(i)}   cp(i) = ${dist.cdf(i)}   p = $p")
    }
    val rv = dist.randomVariable
    rv.advanceToNextSubStream()
    val data = rv.sample(200)

//    val os = data.orderStatistics()
//    println(os.joinToString())
//    var bp = PMFModeler.equalizedCDFBreakPoints(data.size, dist)
//    bp = Histogram.addLowerLimit(0.0, bp)
//    bp = Histogram.addUpperLimit(Double.POSITIVE_INFINITY, bp)
//    println(bp.joinToString())
    println()
    val test = ChiSquaredTestPMF(data, dist, 0)
    println(test)
}