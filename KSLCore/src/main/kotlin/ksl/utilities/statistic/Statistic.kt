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
package ksl.utilities.statistic

import ksl.utilities.*
import ksl.utilities.distributions.*
import ksl.utilities.distributions.fitting.PDFModeler
import kotlin.collections.max
import kotlin.math.*

private var StatCounter: Int = 0

enum class EmpDistType {
    Base, Continuity1, Continuity2
}

/**
 * The Statistic class allows the collection of summary statistics on data via
 * the collect() methods.  The primary statistical summary is for the statistical moments.
 * Creates a Statistic with the given name
 *
 * @param name an optional String representing the name of the statistic
 * @param values an optional array of values to collect on
 */
class Statistic(name: String = "Statistic_${++StatCounter}", values: DoubleArray? = null) :
    AbstractStatistic(name) {

    /**
     * Holds the first 4 statistical central moments
     */
    private var myMoments: DoubleArray = DoubleArray(5)

    /**
     * Holds the number of observations observed
     */
    private var myNum = 0.0

    /**
     * Holds the last value observed
     */
    private var myValue = 0.0

    /**
     * Holds the sum the lag-1 data, i.e. from the second data point on variable
     * for collecting lag1 covariance
     */
    private var mySumXX = 0.0

    /**
     * Holds the first observed data point, needed for von-neuman statistic
     */
    private var myFirstX = 0.0

    /**
     * Holds sum = sum + j*x
     */
    private var myJsum = 0.0

    private var myMin = Double.POSITIVE_INFINITY

    private var myMax = Double.NEGATIVE_INFINITY

    override var negativeCount = 0.0
        private set

    override var zeroCount = 0.0
        private set

    init {
        if (values != null) {
            for (x in values) {
                collect(x)
            }
        }
    }

    /**
     * Creates a Statistic based on the provided array
     *
     * @param values an array of values to collect statistics on
     */
    constructor(values: DoubleArray?) : this("Statistic_${++StatCounter}", values)

    override val count: Double
        get() = myMoments[0]

    override val sum: Double
        get() = myMoments[1] * myMoments[0]

    override val average: Double
        get() = if (myMoments[0] < 1.0) {
            Double.NaN
        } else myMoments[1]

    override val deviationSumOfSquares: Double
        get() = myMoments[2] * myMoments[0]

    override val variance: Double
        get() = if (myMoments[0] < 2.0) {
            Double.NaN
        } else deviationSumOfSquares / (myMoments[0] - 1.0)

    override val min: Double
        get() = myMin

    override val max: Double
        get() = myMax

    override val kurtosis: Double
        get() {
            if (myMoments[0] < 4.0) {
                return Double.NaN
            }
            val n = myMoments[0]
            val n1 = n - 1.0
            val v = variance
            val d = (n - 1.0) * (n - 2.0) * (n - 3.0) * v * v
            val t = n * (n + 1.0) * n * myMoments[4] - 3.0 * n1 * n1 * n1 * v * v
            return t / d
        }

    override val skewness: Double
        get() {
            if (myMoments[0] < 3.0) {
                return Double.NaN
            }
            val n = myMoments[0]
            val v = variance
            val s = sqrt(v)
            val d = (n - 1.0) * (n - 2.0) * v * s
            val t = n * n * myMoments[3]
            return t / d
        }

    override val standardError: Double
        get() = if (myMoments[0] < 1.0) {
            Double.NaN
        } else standardDeviation / sqrt(myMoments[0])

    override val lag1Covariance: Double
        get() = if (myNum > 2.0) {
            val c1 = mySumXX - (myNum + 1.0) * myMoments[1] * myMoments[1] + myMoments[1] * (myFirstX + myValue)
            c1 / myNum
        } else {
            Double.NaN
        }

    override val lag1Correlation: Double
        get() = if (myNum > 2.0) {
            lag1Covariance / myMoments[2]
        } else {
            Double.NaN
        }

    override val vonNeumannLag1TestStatistic: Double
        get() = if (myNum > 2.0) {
            val r1 = lag1Correlation
            val t =
                (myFirstX - myMoments[1]) * (myFirstX - myMoments[1]) + (myValue - myMoments[1]) * (myValue - myMoments[1])
            val b = 2.0 * myNum * myMoments[2]
            val v = sqrt((myNum * myNum - 1.0) / (myNum - 2.0)) * (r1 + t / b)
            v
        } else {
            Double.NaN
        }

    /**
     * Creates an instance of Statistic that is a copy of the supplied Statistic
     * All internal state is the same. The only exception is for the id of the returned Statistic.
     *
     * @return a copy of the supplied Statistic
     */
    fun instance(): Statistic {
        val s = Statistic(name)
        s.numberMissing = numberMissing
        s.myFirstX = myFirstX
        s.myMax = myMax
        s.myMin = myMin
        s.confidenceLevel = confidenceLevel
        s.myJsum = myJsum
        s.myValue = myValue
        s.myNum = myNum
        s.mySumXX = mySumXX
        s.myMoments = myMoments.copyOf()
        s.lastValue = lastValue
        s.negativeCount = negativeCount
        s.zeroCount = zeroCount
        return s
    }

    /**
     * Returns the 2nd statistical central moment
     *
     * @return the 2nd statistical central moment
     */
    val centralMoment2 = myMoments[2]

    /**
     * Returns the 3rd statistical central moment
     *
     * @return the 3rd statistical central moment
     */
    val centralMoment3 = myMoments[3]

    /**
     * Returns the 4th statistical central moment
     *
     * @return the 4th statistical central moment
     */
    val centralMoment4 = myMoments[4]

    /**
     * The 0th moment is the count, the 1st central moment zero,
     * the 2nd, 3rd, and 4th central moments
     *
     * @return an array holding the central moments, 0, 1, 2, 3, 4
     */
    val centralMoments: DoubleArray
        get() = myMoments.copyOf()

    /**
     * Returns the 2nd statistical raw moment (about zero)
     *
     * @return the 2nd statistical raw moment (about zero)
     */
    val rawMoment2 = myMoments[2] + average * average

    /**
     * Returns the 3rd statistical raw moment (about zero)
     *
     * @return the 3rd statistical raw moment (about zero)
     */
    val rawMoment3: Double
        get() {
            val m3 = centralMoment3
            val mr2 = rawMoment2
            val mu = average
            return m3 + 3.0 * mu * mr2 - 2.0 * mu * mu * mu
        }

    /**
     * Returns the 4th statistical raw moment (about zero)
     *
     * @return the 4th statistical raw moment (about zero)
     */
    val rawMoment4: Double
        get() {
            val m4 = centralMoment4
            val mr3 = rawMoment3
            val mr2 = rawMoment2
            val mu = average
            return m4 + 4.0 * mu * mr3 - 6.0 * mu * mu * mr2 + 3.0 * mu * mu * mu * mu
        }

    /**
     * Checks if the supplied value falls within getAverage() +/- getHalfWidth()
     *
     * @param mean the value to check
     * @return true if the supplied value falls within getAverage() +/-
     * getHalfWidth()
     */
    fun checkMean(mean: Double): Boolean {
        val a = average
        val hw = halfWidth
        val ll = a - hw
        val ul = a + hw
        return mean in ll..ul
    }

    /**
     * Returns the half-width for a confidence interval on the mean with
     * confidence level  based on StudentT distribution
     *
     * @param level the confidence level
     * @return the half-width
     */
    override fun halfWidth(level: Double): Double {
        require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
        if (count <= 1.0) {
            return Double.NaN
        }
        val dof = count - 1.0
        val alpha = 1.0 - level
        val p = 1.0 - alpha / 2.0
        val t = StudentT.invCDF(dof, p)
        return t * standardError
    }

    override fun leadingDigitRule(multiplier: Double): Int {
        return floor(log10(multiplier * standardError)).toInt()
    }

    /**
     * @return the p-value associated with the current Von Neumann Lag 1 Test Statistic, or Double.NaN
     */
    fun vonNeumannLag1TestStatisticPValue(): Double {
        if (vonNeumannLag1TestStatistic.isNaN()) {
            return Double.NaN
        }
        return Normal.stdNormalComplementaryCDF(vonNeumannLag1TestStatistic)
    }

    /**
     * Returns the observation weighted sum of the data i.e. sum = sum + j*x
     * where j is the observation number and x is jth observation
     *
     * @return the observation weighted sum of the data
     */
    val obsWeightedSum: Double = myJsum

    override var value: Double
        get() = lastValue
        set(value) {
            collect(value)
        }

    override fun collect(obs: Double) {
        if (obs.isMissing()) {
            numberMissing++
            return
        }
        if (obs < 0.0) {
            negativeCount++
        }
        if (obs == 0.0) {
            zeroCount++
        }
        // update moments
        myNum = myNum + 1
        myJsum = myJsum + myNum * obs
        val n = myMoments[0]
        val n1 = n + 1.0
        val n2 = n * n
        val delta = (myMoments[1] - obs) / n1
        val d2 = delta * delta
        val d3 = delta * d2
        val r1 = n / n1
        myMoments[4] = (1.0 + n * n2) * d2 * d2 + 6.0 * myMoments[2] * d2 + 4.0 * myMoments[3] * delta + myMoments[4]
        myMoments[4] *= r1
        myMoments[3] = (1.0 - n2) * d3 + 3.0 * myMoments[2] * delta + myMoments[3]
        myMoments[3] *= r1
        myMoments[2] = (1.0 + n) * d2 + myMoments[2]
        myMoments[2] *= r1
        myMoments[1] = myMoments[1] - delta
        myMoments[0] = n1

        // to collect lag 1 cov, we need x(1)
        if (myNum == 1.0) {
            myFirstX = obs
        }

        // to collect lag 1 cov, we must provide new x and previous x
        // to collect lag 1 cov, we must sum x(i) and x(i+1)
        if (myNum >= 2.0) {
            mySumXX = mySumXX + obs * myValue
        }

        // update min, max, current value
        if (obs > myMax) {
            myMax = obs
        }
        if (obs < myMin) {
            myMin = obs
        }
        myValue = obs
        lastValue = obs
        notifyObservers(lastValue)
        emitter.emit(lastValue)
    }

    override fun reset() {
        super.reset()
        myValue = Double.NaN
//        myValue = 0.0
        myMin = Double.POSITIVE_INFINITY
        myMax = Double.NEGATIVE_INFINITY
        myNum = 0.0
        myJsum = 0.0
        mySumXX = 0.0
        for (i in myMoments.indices) {
            myMoments[i] = 0.0
        }
        zeroCount = 0.0
        negativeCount = 0.0
    }

    fun asString(): String {
        val sb = StringBuilder("Statistic{")
        sb.append("name='").append(name).append('\'')
        sb.append(", n=").append(count)
        sb.append(", avg=").append(average)
        sb.append(", sd=").append(standardDeviation)
        sb.append(", ci=").append(confidenceInterval.toString())
        sb.append('}')
        return sb.toString()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("ID ")
        sb.append(id)
        sb.appendLine()
        sb.append("Name ")
        sb.append(name)
        sb.appendLine()
        sb.append("Number ")
        sb.append(count)
        sb.appendLine()
        sb.append("Average ")
        sb.append(average)
        sb.appendLine()
        sb.append("Standard Deviation ")
        sb.append(standardDeviation)
        sb.appendLine()
        sb.append("Standard Error ")
        sb.append(standardError)
        sb.appendLine()
        sb.append("Half-width ")
        sb.append(halfWidth)
        sb.appendLine()
        sb.append("Confidence Level ")
        sb.append(confidenceLevel)
        sb.appendLine()
        sb.append("Confidence Interval ")
        sb.append(confidenceInterval)
        sb.appendLine()
        sb.append("Minimum ")
        sb.append(min)
        sb.appendLine()
        sb.append("Maximum ")
        sb.append(max)
        sb.appendLine()
        sb.append("Sum ")
        sb.append(sum)
        sb.appendLine()
        sb.append("Variance ")
        sb.append(variance)
        sb.appendLine()
        sb.append("Deviation Sum of Squares ")
        sb.append(deviationSumOfSquares)
        sb.appendLine()
        sb.append("Last value collected ")
        sb.append(lastValue)
        sb.appendLine()
        sb.append("Kurtosis ")
        sb.append(kurtosis)
        sb.appendLine()
        sb.append("Skewness ")
        sb.append(skewness)
        sb.appendLine()
        sb.append("Lag 1 Covariance ")
        sb.append(lag1Covariance)
        sb.appendLine()
        sb.append("Lag 1 Correlation ")
        sb.append(lag1Correlation)
        sb.appendLine()
        sb.append("Von Neumann Lag 1 Test Statistic ")
        sb.append(vonNeumannLag1TestStatistic)
        sb.appendLine()
        sb.append("Number of missing observations ")
        sb.append(numberMissing)
        sb.appendLine()
        sb.append("Lead-Digit Rule(1) ")
        sb.append(leadingDigitRule(1.0))
        sb.appendLine()
        return sb.toString()
    }

    /**
     * Returns the summary statistics values Name Count Average Std. Dev.
     *
     * @return the string of summary statistics
     */
    val summaryStatistics: String
        get() {
            val format = "%-50s \t %12d \t %12f \t %12f"
            val n = count.toInt()
            val avg = average
            val std = standardDeviation
            val name = name
            return String.format(format, name, n, avg, std)
        }

    /**
     * Returns the header for the summary statistics Name Count Average Std.
     * Dev.
     *
     * @return the header
     */
    val summaryStatisticsHeader: String
        get() = String.format("%-50s \t %12s \t %12s \t %12s", "Name", "Count", "Average", "Std. Dev.")

    /**
     * Estimates the number of observations needed in order to obtain a
     * getConfidenceLevel() confidence interval with plus/minus the provided
     * half-width
     *
     * @param desiredHW the desired half-width, must be greater than zero
     * @return the estimated sample size
     */
    fun estimateSampleSize(desiredHW: Double): Long {
        require(desiredHW > 0.0) { "The desired half-width must be > 0" }
        val cl = this.confidenceLevel
        val a = 1.0 - cl
        val a2 = a / 2.0
        val z = Normal.stdNormalInvCDF(1.0 - a2)
        val s = standardDeviation
        val m = z * s / desiredHW * (z * s / desiredHW)
        return (m + .5).roundToLong()
    }

    companion object {

        /**
         * Uses definition 7, as per R definitions
         *
         * @param unsortedData the array of data. The data will be sorted as a side effect of the call
         * @param p the percentile, must be within (0, 1)
         * @return the quantile
         */
        fun quantile(unsortedData: DoubleArray, p: Double): Double {
            require((p <= 0.0) || (p < 1.0)) { "Percentile value must be (0,1)" }
            val n = unsortedData.size
            if (n == 1) {
                return unsortedData[0]
            }
            unsortedData.sort()
            return quantileFromSortedData(unsortedData, p)
        }

        /**
         * Uses definition 7, as per R definitions
         *
         * @param unsortedData the array of data. The data will be sorted as a side effect of the call
         * @param p the percentile array, each element must be within (0, 1)
         * @return the quantiles associated with each value of [p]. The default values for p
         * are 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95
         */
        fun quantiles(
            unsortedData: DoubleArray,
            p: DoubleArray = doubleArrayOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95)
        ): DoubleArray {
            require(p.isNotEmpty()) { "The array of requested percentiles was empty!" }
            unsortedData.sort()
            return DoubleArray(p.size) { quantileFromSortedData(unsortedData, p[it]) }
        }

        /**
         * Uses definition 7, as per R definitions
         *
         * @param sortedData the array of data. It is assumed that the data is already sorted. This
         * requirement is not checked!
         * @param p the percentile array, each element must be within (0, 1)
         * @return the quantiles associated with each value of [p]. The default values for p
         * are 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95
         */
        fun quantilesFromSortedData(
            sortedData: DoubleArray,
            p: DoubleArray = doubleArrayOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95)
        ): DoubleArray {
            require(p.isNotEmpty()) { "The array of requested percentiles was empty!" }
            return DoubleArray(p.size) { quantileFromSortedData(sortedData, p[it]) }
        }

        /**
         * Uses definition 7, as per R definitions
         *
         * @param sortedData the array of data. It is assumed that the data is already sorted. This
         * requirement is not checked!
         * @param p the percentile, must be within (0, 1)
         * @return the quantile associated with the value of [p]
         */
        fun quantileFromSortedData(sortedData: DoubleArray, p: Double): Double {
            require(sortedData.isNotEmpty()) { "There were no observations in the provided data. The array was empty" }
            val n = sortedData.size
            if (n == 1) {
                return sortedData[0]
            }
            val index = 1 + (n - 1) * p
            if (index < 1.0) {
                return sortedData[0]
            }
            if (index >= n) {
                return sortedData[n - 1]
            }
            var lo = floor(index).toInt()
            var hi = ceil(index).toInt()
            val h = index - lo
            // correct for 0 based arrays
            lo = lo - 1
            hi = hi - 1
            return (1.0 - h) * sortedData[lo] + h * sortedData[hi]
        }

        /**
         * As per Apache Math commons definition
         *
         * @param data the array of data. will be sorted
         * @param p the percentile, must be within (0, 1)
         * @return the percentile
         */
        fun percentile(data: DoubleArray, p: Double): Double {
            require(data.isNotEmpty()) { "There were no observations in the provided data. The array was empty" }
            require((p <= 0.0) || (p < 1.0)) { "Percentile value must be (0,1)" }
            val n = data.size
            if (n == 1) {
                return data[0]
            }
            data.sort()
            val pos = p * (n + 1)
            return if (pos < 1.0) {
                data[0]
            } else if (pos >= n) {
                data[n - 1]
            } else {
                val d = pos - floor(pos)
                val fpos = floor(pos).toInt() - 1 // correct for 0 based arrays
                val lower = data[fpos]
                val upper = data[fpos + 1]
                lower + d * (upper - lower)
            }
        }

        /**
         * Returns the median of the data. The array is sorted
         *
         * @param data the array of data
         * @return the median of the data
         */
        fun median(data: DoubleArray): Double {
            require(data.isNotEmpty()) { "There were no observations in the provided data. The array was empty" }
            if (data.size == 1) {
                return data[0]
            }
            data.sort()
            val size = data.size
            val median = if (size % 2 == 0) { //even
                val firstIndex = size / 2 - 1
                val secondIndex = firstIndex + 1
                val firstValue = data[firstIndex]
                val secondValue = data[secondIndex]
                (firstValue + secondValue) / 2.0
            } else { //odd
                val index = ceil(size / 2.0).toInt()
                data[index]
            }
            return median
        }

        /**
         * Estimate the sample size based on a normal approximation
         *
         * @param desiredHW the desired half-width (must be bigger than 0)
         * @param stdDev    the standard deviation (must be bigger than or equal to 0)
         * @param level     the confidence level (must be between 0 and 1)
         * @return the estimated sample size
         */
        fun estimateSampleSize(desiredHW: Double, stdDev: Double, level: Double = 0.95): Long {
            require(desiredHW > 0.0) { "The desired half-width must be > 0" }
            require(stdDev >= 0.0) { "The desired std. dev. must be >= 0" }
            require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
            val a = 1.0 - level
            val a2 = a / 2.0
            val z = Normal.stdNormalInvCDF(1.0 - a2)
            val m = z * stdDev / desiredHW * (z * stdDev / desiredHW)
            return (m + .5).roundToLong()
        }

        /**
         * Estimate the sample size based on iterating the half-width equation based on the
         * Student-T distribution:  hw = t(1-alpha/2, n-1)*s/sqrt(n) <= epsilon
         *
         * @param desiredHW the desired half-width (must be bigger than 0)
         * @param initStdDevEst  an initial estimate of the standard deviation (must be bigger than or equal to 0)
         * @param level     the confidence level (must be between 0 and 1)
         * @return the estimated sample size
         */
        fun estimateSampleSizeViaStudentT(desiredHW: Double, initStdDevEst: Double, level: Double = 0.95): Long {
            require(desiredHW > 0.0) { "The desired half-width must be > 0" }
            require(initStdDevEst >= 0.0) { "The desired std. dev. must be >= 0" }
            require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
            val a = 1.0 - level
            val a2 = a / 2.0
            val p = 1.0 - a2
            var n = 1.0
            do {
                n = n + 1
                val h = (StudentT.invCDF(n, p) * initStdDevEst) / sqrt(n)
            } while (h <= desiredHW)
            return n.toLong()
        }

        /**
         *  Computes the empirical probabilities based on [n] order statistics using
         *  one of the three types:
         *  Base: i/n
         *  Continuity1: (i-0.5)/n
         *  Continuity2: (i-0.375)/(n + 0.25)
         *  The default [type] is Continuity1.
         *  @return the empirical probabilities
         */
        fun empiricalProbabilities(n: Int, type: EmpDistType = EmpDistType.Continuity1): DoubleArray {
            require(n >= 1) { "The number of observations must be >=1" }
            return when (type) {
                EmpDistType.Base -> DoubleArray(n) { i -> ((i + 1.0) / n) }
                EmpDistType.Continuity1 -> DoubleArray(n) { i -> (i + 1.0 - 0.5) / n }
                EmpDistType.Continuity2 -> DoubleArray(n) { i -> (i + 1.0 - 0.375) / (n + 0.25) }
            }
        }

        /**
         *  Computes the empirical quantiles based on the empirical probabilities for
         *  a data set of size [n] using the supplied [quantileFunction] inverse
         *  CDF. The [type] represents the continuity type as per the empiricalProbabilities()
         *  function.
         */
        fun empiricalQuantiles(
            n: Int,
            quantileFunction: InverseCDFIfc,
            type: EmpDistType = EmpDistType.Continuity1
        ): DoubleArray {
            val p = empiricalProbabilities(n, type)
            return DoubleArray(p.size) { i -> quantileFunction.invCDF(p[i]) }
        }

        /**
         *  Computes the proportion of the observations that are less than
         *  or equal to the supplied value of x. If the array is empty,
         *  then 0.0 is returned.
         */
        fun empiricalCDF(data: DoubleArray, x: Double): Double {
            if (data.isEmpty())
                return 0.0
            val n = data.size.toDouble()
            return data.countLessEqualTo(x) / n
        }

        /**
         *  Computes the box plot summaries for the data within the map
         */
        fun boxPlotSummaries(dataMap: Map<String, DoubleArray>): Map<String, BoxPlotSummary> {
            val m = mutableMapOf<String, BoxPlotSummary>()
            for ((name, data) in dataMap) {
                m[name] = BoxPlotSummary(data, name)
            }
            return m
        }

        /**
         *  Computes the statistical summaries for the data within the map
         */
        fun statisticalSummaries(dataMap: Map<String, DoubleArray>): Map<String, StatisticIfc> {
            val m = mutableMapOf<String, StatisticIfc>()
            for ((name, data) in dataMap) {
                m[name] = Statistic(name, data)
            }
            return m
        }

        /**
         *  Computes the confidence intervals for the data in the map
         */
        fun confidenceIntervals(dataMap: Map<String, DoubleArray>, level: Double = 0.95): Map<String, Interval> {
            val m = mutableMapOf<String, Interval>()
            val s = statisticalSummaries(dataMap)
            for ((name, stat) in s) {
                m[name] = stat.confidenceInterval(level)
            }
            return m
        }

        /**
         * Gets an array of the partial sum process for the provided data Based on
         * page 2575 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation for producing a partial sum plot The
         * batch means array is used as the data
         *
         * @param bm The BatchStatistic
         * @return n array of the partial sums
         */
        fun partialSums(bm: BatchStatistic): DoubleArray {
            val avg: Double = bm.average
            val data: DoubleArray = bm.batchMeans
            return partialSums(avg, data)
        }

        /**
         * Gets an array of the partial sum process for the provided data Based on
         * page 2575 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation for producing a partial sum plot
         *
         * @param avg the average of the supplied data array
         * @param data the data
         * @return the array of partial sums
         */
        fun partialSums(avg: Double, data: DoubleArray): DoubleArray {
            val n = data.size
            val s = DoubleArray(n + 1)
            if (n == 1) {
                s[0] = 0.0
                s[1] = 0.0
                return s
            }
            // first pass computes cum sums
            s[0] = 0.0
            for (j in 1..n) {
                s[j] = s[j - 1] + data[j - 1]
            }
            // second pass computes partial sums
            for (j in 1..n) {
                s[j] = j * avg - s[j]
            }
            return s
        }

        /**
         * Uses the batch means array from the BatchStatistic to compute the
         * positive bias test statistic
         *
         * @param bm the BatchStatistic
         * @return the positive bias test statistic
         */
        fun positiveBiasTestStatistic(bm: BatchStatistic): Double {
            val data: DoubleArray = bm.batchMeans
            return positiveBiasTestStatistic(data)
        }

        /**
         * Computes initialization bias (positive) test statistic based on algorithm
         * on page 2580 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation
         *
         * @param data the data
         * @return test statistic to be compared with F distribution
         */
        fun positiveBiasTestStatistic(data: DoubleArray): Double {
            //find min and max of partial sum series!
            val n = data.size / 2
            val x1 = data.copyOfRange(0, n)
            val x2 = data.copyOfRange(n + 1, 2 * n)
            val s = Statistic()
            s.collect(x1)
            val a1: Double = s.average
            s.reset()
            s.collect(x2)
            val a2: Double = s.average
            val ps1 = partialSums(a1, x1)
            val ps2 = partialSums(a2, x2)
            val mi1: Int = KSLArrays.indexOfMax(ps1)
            val max1: Double = KSLArrays.max(ps1)
            val mi2: Int = KSLArrays.indexOfMax(ps2)
            val max2: Double = KSLArrays.max(ps2)
            val num = mi2 * (n - mi2) * max1 * max1
            val denom = mi1 * (n - mi1) * max2 * max2
            if (max2 == 0.0) {
                return Double.NaN
            }
            return if (denom == 0.0) {
                Double.NaN
            } else num / denom
        }

        /**
         * Uses the batch means array from the BatchStatistic to compute the
         * positive bias test statistic
         *
         * @param bm the BatchStatistic
         * @return the computed test statistic
         */
        fun negativeBiasTestStatistic(bm: BatchStatistic): Double {
            val data: DoubleArray = bm.batchMeans
            return negativeBiasTestStatistic(data)
        }

        /**
         * Computes initialization bias (negative) test statistic based on algorithm
         * on page 2580 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation
         *
         * @param data the data to test
         * @return test statistic to be compared with F distribution
         */
        fun negativeBiasTestStatistic(data: DoubleArray): Double {
            //find min and max of partial sum series!
            val n = data.size / 2
            val x1 = data.copyOfRange(0, n)
            val x2 = data.copyOfRange(n + 1, 2 * n)
            val s = Statistic()
            s.collect(x1)
            val a1: Double = s.average
            s.reset()
            s.collect(x2)
            val a2: Double = s.average
            val ps1 = partialSums(a1, x1)
            val ps2 = partialSums(a2, x2)
            val mi1: Int = KSLArrays.indexOfMin(ps1)
            val min1: Double = KSLArrays.min(ps1)
            val mi2: Int = KSLArrays.indexOfMin(ps2)
            val min2: Double = KSLArrays.min(ps2)
            val num = mi2 * (n - mi2) * min1 * min1
            val denom = mi1 * (n - mi1) * min2 * min2
            if (min2 == 0.0) {
                return Double.NaN
            }
            return if (denom == 0.0) {
                Double.NaN
            } else num / denom
        }

        fun ksTestStatistic(data: DoubleArray, fn: CDFIfc): Double {
            val tp = data.orderStatistics()
            tp.mapInPlace { x -> fn.cdf(x) }
            val n = tp.size
            val ep = empiricalProbabilities(n, EmpDistType.Base)
            val dp = KSLArrays.subtractElements(ep, tp).max()
            val epm = KSLArrays.subtractConstant(ep, (1.0 / n))
            val dm = KSLArrays.subtractElements(tp, epm).max()
            return max(dp, dm)
        }

        /**
         *  Computes the Pearson correlation between the elements of the array
         *  based on n = min(x.size, y.size) elements.  If no elements, then
         *  Double.NaN is returned.
         */
        fun pearsonCorrelation(x: DoubleArray, y: DoubleArray): Double {
            val s = StatisticXY()
            val n = min(x.size, y.size)
            if (n == 0) {
                return Double.NaN
            }
            for (i in 0 until n) {
                s.collectXY(x[i], y[i])
            }
            return s.correlationXY
        }

        /**
         *  Computes the lag-k auto-correlation for the supplied array
         *  [lag] must be greater than or equal to 0 and less than x.size.
         *  [lag] = 0 always returns 1.0
         *  The array [x] must have 2 or more elements.
         *  If you need multiple values for different lags, then using
         *  autoCorrelations() is more efficient.
         */
        fun autoCorrelation(x: DoubleArray, lag: Int): Double {
            require(x.size >= 2) { " There must be 2 or more elements in the array" }
            require(lag >= 0) { "The lag must be >= 0" }
            require(lag < x.size) { "The lag must be < ${x.size}" }
            if (lag == 0) {
                return 1.0
            }
            // 1 <= lag < x.size
            val xStat = Statistic(x)
            if (lag == 1) {
                return xStat.lag1Correlation
            }
            val avg = xStat.average
            val dssq = xStat.deviationSumOfSquares
            return (productSumOfSquares(x, avg, lag) / dssq)
        }

        /**
         *  Computes the auto-correlations for k = 1 to and including [maxLag]
         *  The returned array is zero based indexed such that, ac[0] is the lag-1
         *  lag-0 is not returned because it is always 1.0
         */
        fun autoCorrelations(x: DoubleArray, maxLag: Int): DoubleArray {
            require(x.size >= 2) { " There must be 2 or more elements in the array" }
            require(maxLag >= 1) { "The number of lags requested must be >= 1" }
            require(maxLag < x.size) { "The number of lags requested must be < ${x.size}" }
            val ac = DoubleArray(maxLag) // no need to include 0 lag
            val xStat = Statistic(x)
            ac[0] = xStat.lag1Correlation
            if (maxLag == 1) {
                return ac
            }
            val avg = xStat.average
            val dssq = xStat.deviationSumOfSquares
            for (k in 2..maxLag) {
                ac[k - 1] = productSumOfSquares(x, avg, k) / dssq
            }
            return ac
        }

        private fun productSumOfSquares(x: DoubleArray, xAvg: Double, lag: Int): Double {
            var sq = 0.0
            for (i in 0 until (x.size - lag)) {
                sq = sq + (x[i] - xAvg) * (x[i + lag] - xAvg)
            }
            return sq
        }

        /**
         *  Computes the chi-squared test statistic based on the observed [counts]
         *  and the [expected] counts. The [expected] counts must not contain a zero
         *  value.  The size of the arrays must match.
         */
        fun chiSqTestStatistic(counts: DoubleArray, expected: DoubleArray): Double {
            require(counts.size == expected.size) { "The size of the counts and expected value array must match." }
            require(!expected.hasZero()) { "The expected array contains a 0.0 value" }
            var sum = 0.0
            for (i in counts.indices) {
                sum = sum + ((counts[i] - expected[i]) * (counts[i] - expected[i])) / expected[i]
            }
            return sum
        }

        /**
         *   Computes the chi-squared test statistic based on the supplied [data]
         *   and hypothesized distribution function, [fn].  The break points [breakPoints]
         *   are used to define the binning and tabulation of the counts for the data.
         */
        fun chiSqTestStatistic(
            data: DoubleArray,
            breakPoints: DoubleArray,
            fn: ContinuousDistributionIfc
        ): Double {
            val h = Histogram(breakPoints)
            h.collect(data)
            val ec = PDFModeler.expectedCounts(h, fn)
            return chiSqTestStatistic(h.binCounts, ec)
        }

        /**
         *  Computes the [G test statistic](https://en.wikipedia.org/wiki/G-test) based on the observed [counts]
         *  and the [expected] counts. The [expected] counts must not contain a zero
         *  value.  The size of the arrays must match.
         */
        fun GTestStatistic(counts: DoubleArray, expected: DoubleArray): Double {
            require(counts.size == expected.size) { "The size of the counts and expected value array must match." }
            require(!expected.hasZero()) { "The expected array contains a 0.0 value" }
            var sum = 0.0
            for (i in counts.indices) {
                sum = sum + counts[i]*ln(counts[i]/expected[i])
            }
            return 2.0*sum
        }

        /**
         *  Computes the Anderson-Darling test statistic
         *  https://en.wikipedia.org/wiki/Anderson%E2%80%93Darling_test
         */
        fun andersonDarlingTestStatistic(data: DoubleArray, fn: CDFIfc): Double {
            require(data.isNotEmpty()) { "The data array must have at least one observation" }
            val orderStats = data.orderStatistics()
            val n = data.size
            var sum = 0.0
            for (k in orderStats.indices) {
                val i = k + 1
                sum = sum + (2.0 * i - 1.0) * (ln(fn.cdf(orderStats[k])) + ln(1.0 - fn.cdf(orderStats[n - (k + 1)])))
            }
            sum = sum / n
            return -(n + sum)
        }

        /**
         *  Computes the Cramer-von Mises test statistic
         *  https://en.wikipedia.org/wiki/Cram%C3%A9r%E2%80%93von_Mises_criterion
         */
        fun cramerVonMisesTestStatistic(data: DoubleArray, fn: CDFIfc): Double {
            require(data.isNotEmpty()) { "The data array must have at least one observation" }
            val orderStats = data.orderStatistics()
            val n = data.size.toDouble()
            var sum = 0.0
            for (k in orderStats.indices) {
                val i = k + 1
                val ei = (2.0 * i - 1.0) / (2.0 * n)
                val fi = fn.cdf((orderStats[k]))
                sum = sum + (ei - fi) * (ei - fi)
            }
            return (1.0 / (12.0 * n)) + sum
        }

        /**
         *  Computes the Watson test statistic
         *  https://en.wikipedia.org/wiki/Cram%C3%A9r%E2%80%93von_Mises_criterion
         */
        fun watsonTestStatistic(data: DoubleArray, fn: CDFIfc): Double {
            require(data.isNotEmpty()) { "The data array must have at least one observation" }
            val orderStats = data.orderStatistics()
            val n = data.size
            var sum = 0.0
            var sumfi = 0.0
            for (k in orderStats.indices) {
                val i = k + 1
                val ei = (2.0 * i - 1.0) / 2.0 * n
                val fi = fn.cdf((orderStats[k]))
                sumfi = sumfi + fi
                sum = sum + (ei - fi) * (ei - fi)
            }
            val fBar = sumfi / n
            return ((1.0 / (12.0 * n)) + sum) - n * (fBar - 0.5) * (fBar - 0.5)
        }

        /**
         *  Computes the BIC based on the sample size [sampleSize], the number of parameters
         *  estimated for the model [numParameters], and the maximized value [lnMax] of the log-likelihood
         *  function of the model.
         */
        fun bayesianInfoCriterion(sampleSize: Int, numParameters: Int, lnMax: Double): Double {
            require(sampleSize > 0) { "The size of the sample must be > 0" }
            require(numParameters >= 0) { "The number of parameters estimated must be >= 0" }
            return numParameters * ln(sampleSize.toDouble()) - 2.0 * lnMax
        }

        /**
         *  Computes the AIC based on the sample size [sampleSize], the number of parameters
         *  estimated for the model [numParameters], and the maximized value [lnMax] of the log-likelihood
         *  function of the model.
         */
        fun akaikeInfoCriterion(sampleSize: Int, numParameters: Int, lnMax: Double): Double {
            require(sampleSize > 0) { "The size of the sample must be > 0" }
            require(numParameters >= 0) { "The number of parameters estimated must be >= 0" }
            require(sampleSize - numParameters + 1 > 0) { "The sample size must be > (the number of parameters - 1)" }
            val n = sampleSize.toDouble()
            val k = numParameters.toDouble()
            val num = n - 2.0 * k + 2
            val deNom = n - k + 1.0
            return (num / deNom) - 2.0 * lnMax
        }

        /**
         *  Computes the AIC based on the sample size [sampleSize], the number of parameters
         *  estimated for the model [numParameters], and the maximized value [lnMax] of the log-likelihood
         *  function of the model.
         */
        fun hannanQuinnInfoCriterion(sampleSize: Int, numParameters: Int, lnMax: Double): Double {
            require(sampleSize > 0) { "The size of the sample must be > 0" }
            require(numParameters >= 0) { "The number of parameters estimated must be >= 0" }
            require(sampleSize - numParameters + 1 > 0) { "The sample size must be > (the number of parameters - 1)" }
            val n = sampleSize.toDouble()
            val k = numParameters.toDouble()
            val num = n - 2.0 * k + 2
            val deNom = n - k + 1.0
            return (num / deNom) * ln(ln(n)) - 2.0 * k * lnMax
        }

        /**
         *   Returns the rank of each element in the supplied data
         *   as a separate array of ranks.  If ranks is the returned
         *   array, then ranks[0] indicates the rank of element 0 in
         *   the [data] array.  Ties are handled by assigning the mean of the ranks
         *   that would have been given otherwise, so that the sum of the ranks is preserved.
         *   This is called [fractional ranking](https://en.wikipedia.org/wiki/Ranking)
         */
        fun fractionalRanks(data: DoubleArray): DoubleArray {
            // create the ranks array
            val ranks = DoubleArray(data.size) { 0.0 }
            // Create an auxiliary array of pairs, each pair stores the data as well as its index
            val pairs = Array<Pair<Double, Int>>(data.size) { Pair(data[it], it) }
            // sort according to the data (first element in the pair
            val comparator = compareBy<Pair<Double, Int>> { it.first }
            pairs.sortWith(comparator)
            var r = 1
            var i = 0
            val n = data.size
            while (i < n) {
                var j = i
                // Get no of elements with equal rank
                while ((j < n - 1) && (pairs[j].first == pairs[j + 1].first)) j += 1
                val m = (j - i + 1)
                for (k in 0..<m) {
                    val idx: Int = pairs[i + k].second
                    ranks[idx] = r + (m - 1.0) * 0.5
                }
                // Increment rank and i
                r += m
                i = (i + m)
            }
            return ranks
        }

        /**
         *   Returns the rank of each element in the supplied data
         *   as a separate array of ranks.  If ranks is the returned
         *   array, then ranks[0] indicates the rank of element 0 in
         *   the [data] array.  Each element gets a unique rank based
         *   on the sorted order of the [data] array. Ties are handled arbitrarily
         *   based on the underlying sorting mechanism, which seems to be
         *   predicated on the "first" ranking method for the R rank() function.
         *
         *   This is called [ordinal ranking](https://en.wikipedia.org/wiki/Ranking)
         */
        fun ordinalRanks(data: DoubleArray): DoubleArray {
            // create the ranks array
            val ranks = DoubleArray(data.size) { 0.0 }
            // Create an auxiliary array of pairs, each pair stores the data as well as its index
            val pairs = Array<Pair<Double, Int>>(data.size) { Pair(data[it], it) }
            // sort according to the data (first element in the pair
            val comparator = compareBy<Pair<Double, Int>> { it.first }
            pairs.sortWith(comparator)
            var r = 1
            for ((k, pair) in pairs.withIndex()) {
                ranks[pair.second] = k + 1.0
            }
            return ranks
        }

        /**
         *   Returns the rank of each element in the supplied data
         *   as a separate array of ranks.  If ranks is the returned
         *   array, then ranks[0] indicates the rank of element 0 in
         *   the [data] array.  In dense ranking, items that compare equally receive
         *   the same ranking number, and the next items receive the immediately following ranking number.
         *   This is called [dense ranking](https://en.wikipedia.org/wiki/Ranking)
         */
        fun denseRanks(data: DoubleArray): DoubleArray {
            // create the ranks array
            val ranks = DoubleArray(data.size) { 0.0 }
            // Create an auxiliary array of pairs, each pair stores the data as well as its index
            val pairs = Array<Pair<Double, Int>>(data.size) { Pair(data[it], it) }
            // sort according to the data (first element in the pair
            val comparator = compareBy<Pair<Double, Int>> { it.first }
            pairs.sortWith(comparator)
            var r = 1
            ranks[pairs[0].second] = r.toDouble()
            for (k in 1.until(data.size)) {
                if (pairs[k].first > pairs[k - 1].first) {
                    r = r + 1
                }
                ranks[pairs[k].second] = r.toDouble()
            }
            return ranks
        }
    }

}

fun main() {
    // test ranking function
    val y = doubleArrayOf(1.0, 2.0, 5.0, 2.0, 1.0, 25.0, 2.0)
    //   val y = doubleArrayOf(1.0, 2.0, 5.0, 2.0, 1.0, 25.0, 2.0, 1.0, 1.0, 1.0)
//    val y = doubleArrayOf(1.0, 1.0, 2.0, 3.0, 3.0, 4.0, 5.0, 5.0, 5.0)
    print("Data             = ")
    println(y.joinToString())
    val r = Statistic.fractionalRanks(y)
    print("Fractional Ranks = ")
    println(r.joinToString())

    println()

    print("Data          = ")
    println(y.joinToString())
    val o = Statistic.ordinalRanks(y)
    print("Ordinal Ranks = ")
    println(o.joinToString())
    println()
    print("Data        = ")
    println(y.joinToString())
    val dr = Statistic.denseRanks(y)
    print("Dense Ranks = ")
    println(dr.joinToString())
}