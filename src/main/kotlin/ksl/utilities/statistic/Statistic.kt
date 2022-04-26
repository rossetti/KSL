/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.statistic

import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.StudentT
import kotlin.math.ceil
import kotlin.math.roundToLong

private var StatCounter: Int = 0

/**
 * The Statistic class allows the collection of summary statistics on data via
 * the collect() methods.  The primary statistical summary is for the statistical moments.
 * Creates a Statistic with the given name
 *
 * @param name an optional String representing the name of the statistic
 * @param values an optional array of values to collect on
 */
open class Statistic (name: String = "Statistic_${++StatCounter}", values: DoubleArray? = null) : AbstractStatistic(name) {

    init {
        values?.let { collect(it) }
    }

    /**
     * Holds the number of observations observed
     */
    protected var myNum = 0.0

    /**
     * Holds the last value observed
     */
    protected var myValue = 0.0

    /**
     * Holds the sum the lag-1 data, i.e. from the second data point on variable
     * for collecting lag1 covariance
     */
    protected var mySumXX = 0.0

    /**
     * Holds the first observed data point, needed for von-neuman statistic
     */
    protected var myFirstX = 0.0

    /**
     * Holds the first 4 statistical central moments
     */
    protected var myMoments: DoubleArray = DoubleArray(5)

    /**
     * Holds sum = sum + j*x
     */
    protected var myJsum = 0.0

    protected var myMin = Double.POSITIVE_INFINITY

    protected var myMax = Double.NEGATIVE_INFINITY

    /**
     * Creates a Statistic \based on the provided array
     *
     * @param values an array of values to collect statistics on
     */
    constructor(values: DoubleArray) : this("Statistic_${++StatCounter}", values)

    override val count: Double
        get() = count()
    override val sum: Double
        get() = sum()
    override val average: Double
        get() = average()
    override val deviationSumOfSquares: Double
        get() = deviationSumOfSquares()
    override val variance: Double
        get() = variance()
    override val min: Double
        get() = min()
    override val max: Double
        get() = max()
    override val kurtosis: Double
        get() = kurtosis()
    override val skewness: Double
        get() = skewness()
    override val standardError: Double
        get() = standardError()
    override val lag1Covariance: Double
        get() = lag1Covariance()
    override val lag1Correlation: Double
        get() = lag1Correlation()
    override val vonNeumannLag1TestStatistic: Double
        get() = vonNeumannLag1TestStatistic()

    /**
     * Creates a instance of Statistic that is a copy of the supplied Statistic
     * All internal state is the same (including whether the collection is
     * on or off) and the collection rule. The only exception is for the id of the returned Statistic.
     * If this statistic has saved data, the new instance will also have that data.
     *
     * @return a copy of the supplied Statistic
     */
    fun newInstance(): Statistic {
        val s = Statistic(name)
        s.numberMissing = numberMissing
        s.myFirstX = myFirstX
        s.myMax= myMax
        s.myMin = myMin
        s.confidenceLevel = confidenceLevel
        s.myJsum = myJsum
        s.myValue = myValue
        s.myNum = myNum
        s.mySumXX = mySumXX
        s.myMoments = myMoments.copyOf()
        return s
    }

    fun count(): Double {
        return myMoments[0]
    }

    fun sum(): Double {
        return myMoments[1] * myMoments[0]
    }

    fun average(): Double {
        return if (myMoments[0] < 1.0) {
            Double.NaN
        } else myMoments[1]
    }

    /**
     * Returns the 2nd statistical central moment
     *
     * @return the 2nd statistical central moment
     */
    fun centralMoment2(): Double {
        return myMoments[2]
    }

    /**
     * Returns the 3rd statistical central moment
     *
     * @return the 3rd statistical central moment
     */
    fun centralMoment3(): Double {
        return myMoments[3]
    }

    /**
     * Returns the 4th statistical central moment
     *
     * @return the 4th statistical central moment
     */
    fun centralMoment4(): Double {
        return myMoments[4]
    }

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
    fun rawMoment2(): Double {
        val mu = average
        return myMoments[2] + mu * mu
    }

    /**
     * Returns the 3rd statistical raw moment (about zero)
     *
     * @return the 3rd statistical raw moment (about zero)
     */
    fun rawMoment3(): Double {
        val m3 = centralMoment3()
        val mr2 = rawMoment2()
        val mu = average
        return m3 + 3.0 * mu * mr2 - 2.0 * mu * mu * mu
    }

    /**
     * Returns the 4th statistical raw moment (about zero)
     *
     * @return the 4th statistical raw moment (about zero)
     */
    fun rawMoment4(): Double {
        val m4 = centralMoment4()
        val mr3 = rawMoment3()
        val mr2 = rawMoment2()
        val mu = average
        return m4 + 4.0 * mu * mr3 - 6.0 * mu * mu * mr2 + 3.0 * mu * mu * mu * mu
    }

    fun deviationSumOfSquares(): Double {
        return myMoments[2] * myMoments[0]
    }

    fun variance(): Double {
        return if (myMoments[0] < 2) {
            Double.NaN
        } else deviationSumOfSquares / (myMoments[0] - 1.0)
    }

    fun min(): Double {
        return myMin
    }

    fun max(): Double {
        return myMax
    }

    fun lastValue(): Double {
        return myValue
    }

    fun kurtosis(): Double {
        if (myMoments[0] < 4) {
            return Double.NaN
        }
        val n = myMoments[0]
        val n1 = n - 1.0
        val v = variance
        val d = (n - 1.0) * (n - 2.0) * (n - 3.0) * v * v
        val t = n * (n + 1.0) * n * myMoments[4] - 3.0 * n1 * n1 * n1 * v * v
        return t / d
    }

    fun skewness(): Double {
        if (myMoments[0] < 3) {
            return Double.NaN
        }
        val n = myMoments[0]
        val v = variance
        val s = Math.sqrt(v)
        val d = (n - 1.0) * (n - 2.0) * v * s
        val t = n * n * myMoments[3]
        return t / d
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

    fun standardError(): Double {
        return if (myMoments[0] < 1.0) {
            Double.NaN
        } else standardDeviation / Math.sqrt(myMoments[0])
    }

    override fun leadingDigitRule(a: Double): Int {
        return Math.floor(Math.log10(a * standardError)).toInt()
    }

    fun lag1Covariance(): Double {
        return if (myNum > 2.0) {
            val c1 = mySumXX - (myNum + 1.0) * myMoments[1] * myMoments[1] + myMoments[1] * (myFirstX + myValue)
            c1 / myNum
        } else {
            Double.NaN
        }
    }

    fun lag1Correlation(): Double {
        return if (myNum > 2.0) {
            lag1Covariance / myMoments[2]
        } else {
            Double.NaN
        }
    }

    fun vonNeumannLag1TestStatistic(): Double {
        return if (myNum > 2.0) {
            val r1 = lag1Correlation
            val t = (myFirstX - myMoments[1]) * (myFirstX - myMoments[1]) + (myValue - myMoments[1]) * (myValue - myMoments[1])
            val b = 2.0 * myNum * myMoments[2]
            val v = Math.sqrt((myNum * myNum - 1.0) / (myNum - 2.0)) * (r1 + t / b)
            v
        } else {
            Double.NaN
        }
    }

    fun vonNeumannLag1TestStatisticPValue(): Double {
        return Normal.stdNormalComplementaryCDF(vonNeumannLag1TestStatistic)
    }

    /**
     * Returns the observation weighted sum of the data i.e. sum = sum + j*x
     * where j is the observation number and x is jth observation
     *
     * @return the observation weighted sum of the data
     */
    val obsWeightedSum: Double
        get() = myJsum

    override fun collect(x: Double) {
        super.collect(x)
        // update moments
        myNum = myNum + 1
        myJsum = myJsum + myNum * x
        val n = myMoments[0]
        val n1 = n + 1.0
        val n2 = n * n
        val delta = (myMoments[1] - x) / n1
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
            myFirstX = x
        }

        // to collect lag 1 cov, we must provide new x and previous x
        // to collect lag 1 cov, we must sum x(i) and x(i+1)
        if (myNum >= 2.0) {
            mySumXX = mySumXX + x * myValue
        }

        // update min, max, current value
        if (x > myMax) {
            myMax = x
        }
        if (x < myMin) {
            myMin = x
        }
        myValue = x
    }

    override fun reset() {
        super.reset()
        myValue = Double.NaN
        myMin = Double.POSITIVE_INFINITY
        myMax = Double.NEGATIVE_INFINITY
        myNum = 0.0
        myJsum = 0.0
        mySumXX = 0.0
        for (i in myMoments.indices) {
            myMoments[i] = 0.0
        }
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
        sb.append(System.lineSeparator())
        sb.append("Name ")
        sb.append(name)
        sb.append(System.lineSeparator())
        sb.append("Number ")
        sb.append(count)
        sb.append(System.lineSeparator())
        sb.append("Average ")
        sb.append(average)
        sb.append(System.lineSeparator())
        sb.append("Standard Deviation ")
        sb.append(standardDeviation)
        sb.append(System.lineSeparator())
        sb.append("Standard Error ")
        sb.append(standardError)
        sb.append(System.lineSeparator())
        sb.append("Half-width ")
        sb.append(halfWidth)
        sb.append(System.lineSeparator())
        sb.append("Confidence Level ")
        sb.append(confidenceLevel)
        sb.append(System.lineSeparator())
        sb.append("Confidence Interval ")
        sb.append(confidenceInterval)
        sb.append(System.lineSeparator())
        sb.append("Minimum ")
        sb.append(min)
        sb.append(System.lineSeparator())
        sb.append("Maximum ")
        sb.append(max)
        sb.append(System.lineSeparator())
        sb.append("Sum ")
        sb.append(sum)
        sb.append(System.lineSeparator())
        sb.append("Variance ")
        sb.append(variance)
        sb.append(System.lineSeparator())
        sb.append("Deviation Sum of Squares ")
        sb.append(deviationSumOfSquares)
        sb.append(System.lineSeparator())
        sb.append("Last value collected ")
        sb.append(lastValue)
        sb.append(System.lineSeparator())
//        sb.append(System.lineSeparator())
        sb.append("Kurtosis ")
        sb.append(kurtosis)
        sb.append(System.lineSeparator())
        sb.append("Skewness ")
        sb.append(skewness)
        sb.append(System.lineSeparator())
        sb.append("Lag 1 Covariance ")
        sb.append(lag1Covariance)
        sb.append(System.lineSeparator())
        sb.append("Lag 1 Correlation ")
        sb.append(lag1Correlation)
        sb.append(System.lineSeparator())
        sb.append("Von Neumann Lag 1 Test Statistic ")
        sb.append(vonNeumannLag1TestStatistic)
        sb.append(System.lineSeparator())
        sb.append("Number of missing observations ")
        sb.append(numberMissing)
        sb.append(System.lineSeparator())
        sb.append("Lead-Digit Rule(1) ")
        sb.append(leadingDigitRule(1.0))
        sb.append(System.lineSeparator())
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
         * Returns a statistic that summarizes the passed in array of values
         *
         * @param x the values to compute statistics for
         * @return a Statistic summarizing the data
         */
        fun collectStatistics(x: DoubleArray): Statistic {
            val s = Statistic()
            s.collect(x)
            return s
        }

        /**
         * Creates a instance of Statistic that is a copy of the supplied Statistic
         * All internal state is the same (including whether the collection is
         * on or off) and the collection rule. The only exception is for the id of the returned Statistic
         * If this statistic has saved data, the new instance will also have that data.
         *
         * @param stat the stat to copy
         * @return a copy of the supplied Statistic
         */
        fun instance(stat: Statistic): Statistic {
            val s = Statistic(stat.name)
            s.numberMissing = stat.numberMissing
            s.myFirstX = stat.myFirstX
            s.myMax = stat.myMax
            s.myMin = stat.myMin
            s.confidenceLevel = stat.confidenceLevel
            s.myJsum = stat.myJsum
            s.myValue = stat.myValue
            s.myNum = stat.myNum
            s.mySumXX = stat.mySumXX
            s.myMoments = stat.myMoments.copyOf()
            return s
        }

        /**
         * Returns the index associated with the minimum element in the array For
         * ties, this returns the first found
         *
         * @param x the array of data
         * @return the index associated with the minimum element
         */
        fun indexOfMin(x: DoubleArray): Int {
            var index = 0
            var min = Double.MAX_VALUE
            for (i in x.indices) {
                if (x[i] < min) {
                    min = x[i]
                    index = i
                }
            }
            return index
        }

        /**
         * @param x the array of data
         * @return the minimum value in the array
         */
        fun min(x: DoubleArray): Double {
            return x[indexOfMin(x)]
        }

        /**
         * Returns the index associated with the maximum element in the array For
         * ties, this returns the first found
         *
         * @param x the array of data
         * @return the index associated with the maximum element
         */
        fun indexOfMax(x: DoubleArray): Int {
            var index = 0
            var max = Double.MIN_VALUE
            for (i in x.indices) {
                if (x[i] > max) {
                    max = x[i]
                    index = i
                }
            }
            return index
        }

        /**
         * @param x the array of data
         * @return the maximum value in the array
         */
        fun getMax(x: DoubleArray): Double {
            return x[indexOfMax(x)]
        }

        /**
         * Returns the median of the data. The array is sorted
         *
         * @param data the array of data, must not be null
         * @return the median of the data
         */
        fun median(data: DoubleArray): Double {
            data.sort()
            val size = data.size
            var median = -1.0
            median = if (size % 2 == 0) { //even
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
         * @param data the data to count
         * @param x    the ordinate to check
         * @return the number of data points less than or equal to x
         */
        fun countLessEqualTo(data: DoubleArray, x: Double): Int {
            var cnt = 0
            for (datum in data) {
                if (datum <= x) {
                    cnt++
                }
            }
            return cnt
        }

        /**
         * @param data the data to count
         * @param x    the ordinate to check
         * @return the number of data points less than x
         */
        fun countLessThan(data: DoubleArray, x: Double): Int {
            var cnt = 0
            for (datum in data) {
                if (datum < x) {
                    cnt++
                }
            }
            return cnt
        }

        /**
         * @param data the data to count
         * @param x    the ordinate to check
         * @return the number of data points greater than or equal to x
         */
        fun countGreaterEqualTo(data: DoubleArray, x: Double): Int {
            var cnt = 0
            for (datum in data) {
                if (datum >= x) {
                    cnt++
                }
            }
            return cnt
        }

        /**
         * @param data the data to count
         * @param x    the ordinate to check
         * @return the number of data points greater than x
         */
        fun countGreaterThan(data: DoubleArray, x: Double): Int {
            var cnt = 0
            for (datum in data) {
                if (datum > x) {
                    cnt++
                }
            }
            return cnt
        }

        /**
         * @param data the data to sort
         * @return a copy of the sorted array in ascending order representing the order statistics
         */
        fun orderStatistics(data: DoubleArray): DoubleArray {
            val doubles = data.copyOf()
            doubles.sort()
            return doubles
        }

        /**
         * Estimate the sample size based on a normal approximation
         *
         * @param desiredHW the desired half-width (must be bigger than 0)
         * @param stdDev    the standard deviation (must be bigger than or equal to 0)
         * @param level     the confidence level (must be between 0 and 1)
         * @return the estimated sample size
         */
        fun estimateSampleSize(desiredHW: Double, stdDev: Double, level: Double): Long {
            require(desiredHW > 0.0) { "The desired half-width must be > 0" }
            require(stdDev >= 0.0) { "The desired std. dev. must be >= 0" }
            require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
            val a = 1.0 - level
            val a2 = a / 2.0
            val z = Normal.stdNormalInvCDF(1.0 - a2)
            val m = z * stdDev / desiredHW * (z * stdDev / desiredHW)
            return (m + .5).roundToLong()
        }
    }

}