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

import ksl.utilities.random.rvariable.ExponentialRV


/**
 * A Histogram tabulates data into bins.  The user must specify the break points
 * of the bins, b0, b1, b2, ..., bk, where there are k+1 break points, and k bins.
 * b0 may be Double.NEGATIVE_INFINITY and bk may be Double.POSITIVE_INFINITY.
 *
 *
 * If only one break point is supplied, then the bins are automatically defined as:
 * (Double.NEGATIVE_INFINITY, b0] and (b0, Double.POSITIVE_INFINITY).
 *
 *
 * If two break points are provided, then there is one bin: [b0, b1), any values
 * less than b0 will be counted as underflow and any values [b1, +infinity) will
 * be counted as overflow.
 *
 *
 * If k+1 break points are provided then the bins are defined as:
 * [b0,b1), [b1,b2), [b2,b3), ..., [bk-1,bk) and
 * any values in (-infinity, b0) will be counted as underflow and any values [bk, +infinity) will
 * be counted as overflow. If b0 equals Double.NEGATIVE_INFINITY then there can
 * be no underflow. Similarly, if bk equals Double.POSITIVE_INFINITY there can be no
 * overflow.
 *
 *
 * The break points do not have to define equally sized bins. Static methods within HistogramBIfc
 * are provided to create equal width bins and to create histograms with common
 * characteristics.
 *
 *
 * If any presented value is Double.NaN, then the value is counted as missing
 * and the observation is not tallied towards the total number of observations. Underflow and
 * overflow counts also do not count towards the total number of observations.
 *
 *
 * Statistics are also automatically collected on the collected observations. The statistics
 * do not include missing, underflow, and overflow observations. Statistics are only computed
 * on those observations that were placed (counted) within some bin.
 *
 * @param breakPoints the break points for the histogram, must be strictly increasing
 * @param name an optional name for the histogram
 */
class HistogramB(breakPoints: DoubleArray, name: String? = null) : AbstractStatistic(name),
    HistogramIfc {

    /**
     * holds the binned data
     */
    private val myBins: List<HistogramBin> = HistogramIfc.makeBins(breakPoints)

    /**
     * Lower limit of first histogram bin.
     */
    override val firstBinLowerLimit: Double = myBins[0].lowerLimit

    /**
     * Upper limit of last histogram bin.
     */
    override val lastBinUpperLimit: Double = myBins[myBins.size - 1].upperLimit

    /**
     * Counts of values located below first bin.
     */
    override var underFlowCount: Double = 0.0
        private set

    /**
     * Counts of values located above last bin.
     */
    override var overFlowCount: Double = 0.0
        private set

    /**
     * Collects statistical information
     */
    private val myStatistic: Statistic = Statistic("$name Histogram")

    override val totalCount: Double = myStatistic.count + overFlowCount + underFlowCount

    override val count: Double
        get() = myStatistic.count()
    override val sum: Double
        get() = myStatistic.sum()
    override val average: Double
        get() = myStatistic.average()
    override val deviationSumOfSquares: Double
        get() = myStatistic.deviationSumOfSquares()
    override val variance: Double
        get() = myStatistic.variance()
    override val min: Double
        get() = myStatistic.min()
    override val max: Double
        get() = myStatistic.max()
    override val kurtosis: Double
        get() = myStatistic.kurtosis()
    override val skewness: Double
        get() = myStatistic.skewness()
    override val standardError: Double
        get() = myStatistic.standardError()
    override val lag1Covariance: Double
        get() = myStatistic.lag1Covariance()
    override val lag1Correlation: Double
        get() = myStatistic.lag1Correlation()
    override val vonNeumannLag1TestStatistic: Double
        get() = myStatistic.vonNeumannLag1TestStatistic()

    override fun halfWidth(level: Double): Double {
        return myStatistic.halfWidth(level)
    }

    override fun leadingDigitRule(multiplier: Double): Int {
        return myStatistic.leadingDigitRule(multiplier)
    }

    override fun collect(value: Double) {
        if (value.isMissing()) {
            numberMissing++
            return
        }
        if (value < firstBinLowerLimit) {
            underFlowCount++
        } else if (value >= lastBinUpperLimit) {
            overFlowCount++
        } else {
            val bin = findBin(value)
            bin.increment()
            // collect statistics on only binned observations
            myStatistic.collect(value)
        }
    }

    override fun findBin(x: Double): HistogramBin {
        for (bin in myBins) {
            if (x < bin.upperLimit) {
                return bin
            }
        }
        // bin must be found, but just in case
        val s = "The observation = $x could not be binned!"
        throw IllegalStateException(s)
    }

    override fun reset() {
        numberMissing = 0.0
        myStatistic.reset()
        overFlowCount = 0.0
        underFlowCount = 0.0
        for (bin in myBins) {
            bin.reset()
        }
    }

    override fun binNumber(x: Double): Int {
        val bin = findBin(x)
        return bin.binNumber
    }

    override val numberBins: Int
        get() = myBins.size

    override fun bin(x: Double): HistogramBin {
        val bin = findBin(x)
        return bin.instance()
    }

    override fun bin(binNum: Int): HistogramBin {
        return myBins[binNum - 1].instance()
    }

    override val bins: List<HistogramBin>
        get() {
            val bins: MutableList<HistogramBin> = ArrayList()
            for (bin in myBins) {
                bins.add(bin.instance())
            }
            return bins
        }

    override val binArray: Array<HistogramBin>
        get() {
            return bins.toTypedArray()
        }

    override val breakPoints: DoubleArray
        get() {
            val b = DoubleArray(myBins.size + 1)
            for ((i, bin) in myBins.withIndex()) {
                b[i] = bin.lowerLimit
            }
            b[myBins.size] = bin(numberBins).upperLimit
            return b
        }

    override val binCounts: DoubleArray
        get() {
            val counts = DoubleArray(numberBins)
            for ((i, bin) in myBins.withIndex()) {
                counts[i] = bin.count()
            }
            return counts
        }

    override fun binCount(x: Double): Double {
        return findBin(x).count()
    }

    override fun binCount(binNum: Int): Double {
        return myBins[binNum - 1].count()
    }

    override fun binFraction(binNum: Int): Double {
        val n = myStatistic.count
        return if (n > 0.0) {
            binCount(binNum) / n
        } else {
            Double.NaN
        }
    }

    override fun binFraction(x: Double): Double {
        return binFraction(binNumber(x))
    }

    override fun cumulativeBinCount(x: Double): Double {
        return cumulativeBinCount(binNumber(x))
    }

    override fun cumulativeBinCount(binNum: Int): Double {
        if (binNum < 0) {
            return 0.0
        }
        if (binNum > myBins.size) {
            return myStatistic.count
        }
        var sum = 0.0
        for (i in 1..binNum) {
            sum = sum + binCount(i)
        }
        return sum
    }

    override fun cumulativeBinFraction(binNum: Int): Double {
        val n = myStatistic.count
        return if (n > 0.0) {
            cumulativeBinCount(binNum) / n
        } else {
            Double.NaN
        }
    }

    override fun cumulativeBinFraction(x: Double): Double {
        return cumulativeBinFraction(binNumber(x))
    }

    override fun cumulativeCount(binNum: Int): Double {
        if (binNum < 0) {
            return underFlowCount
        }
        return if (binNum > myBins.size) {
            totalCount
        } else underFlowCount + cumulativeBinCount(binNum)
    }

    override fun cumulativeCount(x: Double): Double {
        return cumulativeCount(binNumber(x))
    }

    override fun cumulativeFraction(binNum: Int): Double {
        val n = totalCount
        return if (n > 0.0) {
            cumulativeCount(binNum) / n
        } else {
            Double.NaN
        }
    }

    override fun cumulativeFraction(x: Double): Double {
        return cumulativeFraction(binNumber(x))
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Histogram: ").append(name)
        sb.append(System.lineSeparator())
        sb.append("-------------------------------------")
        sb.append(System.lineSeparator())
        sb.append("Number of bins = ").append(numberBins)
        sb.append(System.lineSeparator())
        sb.append("First bin starts at = ").append(firstBinLowerLimit)
        sb.append(System.lineSeparator())
        sb.append("Last bin ends at = ").append(lastBinUpperLimit)
        sb.append(System.lineSeparator())
        sb.append("Under flow count = ").append(underFlowCount)
        sb.append(System.lineSeparator())
        sb.append("Over flow count = ").append(overFlowCount)
        sb.append(System.lineSeparator())
        val n = count
        sb.append("Total bin count = ").append(n)
        sb.append(System.lineSeparator())
        sb.append("Total count = ").append(totalCount)
        sb.append(System.lineSeparator())
        sb.append("-------------------------------------")
        sb.append(System.lineSeparator())
        sb.append(String.format("%3s %-12s %-5s %-5s %-5s %-6s", "Bin", "Range", "Count", "CumTot", "Frac", "CumFrac"))
        sb.append(System.lineSeparator())
        //        sb.append("Bin \t Range \t Count \t\t tc \t\t p \t\t cp\n");
        var ct = 0.0
        for (bin in myBins) {
            val c: Double = bin.count()
            ct = ct + c
            val s = String.format("%s %5.1f %5f %6f %n", bin, ct, c / n, ct / n)
            sb.append(s)
        }
        sb.append("-------------------------------------")
        sb.append(System.lineSeparator())
        sb.append("Statistics on data collected within bins:")
        sb.append(System.lineSeparator())
        sb.append("-------------------------------------")
        sb.append(System.lineSeparator())
        sb.append(myStatistic)
        sb.append("-------------------------------------")
        sb.append(System.lineSeparator())
        return sb.toString()
    }

//    /**
//     * Returns the observation weighted sum of the data i.e. sum = sum + j*x
//     * where j is the observation number and x is jth observation
//     *
//     * @return the observation weighted sum of the data
//     */
//    fun getObsWeightedSum(): Double {
//        return myStatistic.obsWeightedSum
//    }


}

fun main() {
    val d = ExponentialRV(2.0)
    val points = HistogramIfc.createBreakPoints(0.0, 10, 0.25)
    val h1: HistogramIfc = HistogramB(points)
    val h2: HistogramIfc = HistogramB(HistogramIfc.addPositiveInfinity(points))
    for (i in 1..100) {
        val x = d.value
        h1.collect(x)
        h2.collect(x)
    }
    println(h1)
    println(h2)
}