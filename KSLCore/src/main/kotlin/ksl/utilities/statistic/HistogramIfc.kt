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

import ksl.utilities.IdentityIfc
import ksl.utilities.KSLArrays
import ksl.utilities.distributions.CDFIfc
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.ProbInRangeIfc
import ksl.utilities.io.plotting.HistogramPlot
import ksl.utilities.isAllEqual
import ksl.utilities.math.KSLMath
import ksl.utilities.multiplyConstant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow


interface HistogramIfc : CollectorIfc, IdentityIfc, StatisticIfc, GetCSVStatisticIfc,
    Comparable<AbstractStatistic> {
    /**
     * @param x the observation to bin
     * @return the bin that the observation falls within
     */
    fun findBin(x: Double): HistogramBin

    /**
     * Bins are numbered starting at 1 through the number of bins
     *
     * @param x double
     * @return int    the number of the bin where x is located
     */
    fun binNumber(x: Double): Int

    /**
     * The number of observations that fell below the first bin's lower limit
     *
     * @return number of observations that fell below the first bin's lower limit
     */
    val underFlowCount: Double

    /**
     * The number of observations that fell past the last bin's upper limit
     *
     * @return number of observations that fell past the last bin's upper limit
     */
    val overFlowCount: Double

    /**
     * @return the number of bins that were defined
     */
    val numberBins: Int

    /**
     * The bin that x falls in. The bin is a copy. It will not
     * reflect observations collected after this call.
     *
     * @param x the data to check
     * @return bin that x falls in
     */
    fun bin(x: Double): HistogramBin

    /**
     * Returns an instance of a Bin for the supplied bin number
     * The bin does not reflect changes to the histogram after
     * this call. May throw IndexOutOfBoundsException
     *
     * @param binNum the bin number to get
     * @return the bin, or null
     */
    fun bin(binNum: Int): HistogramBin

    /**
     * Returns a List of Bins based on the current state of the
     * histogram
     *
     * @return the list of bins
     */
    val bins: List<HistogramBin>

    /**
     * Returns an array of Bins based on the current state of the
     * histogram
     *
     * @return the array of bins
     */
    val binArray: Array<HistogramBin>

    /**
     * @return the break points for the bins
     */
    val breakPoints: DoubleArray

    /**
     * @return the bin counts as an array
     */
    val binCounts: DoubleArray

    val binFractions: DoubleArray
        get() {
            val m = DoubleArray(bins.size)
            val n = count
            for ((index, bin) in bins.withIndex()) {
                m[index] = bin.count() / n
            }
            return m
        }

    /**
     * @return the mid-point of each bin as an array
     */
    val midPoints: DoubleArray
        get() {
            val m = DoubleArray(bins.size)
            for ((index, bin) in bins.withIndex()) {
                m[index] = bin.midPoint
            }
            return m
        }

    /**
     * @return the lower limit of each bin as an array
     */
    val lowerLimits: DoubleArray
        get() {
            val m = DoubleArray(bins.size)
            for ((index, bin) in bins.withIndex()) {
                m[index] = bin.lowerLimit
            }
            return m
        }

    /**
     * @return the upper limit of each bin as an array
     */
    val upperLimits: DoubleArray
        get() {
            val m = DoubleArray(bins.size)
            for ((index, bin) in bins.withIndex()) {
                m[index] = bin.upperLimit
            }
            return m
        }

    /**
     *  @return the width of each bin as an array
     */
    val binWidths: DoubleArray
        get() {
            val m = DoubleArray(bins.size)
            for ((index, bin) in bins.withIndex()) {
                m[index] = bin.width
            }
            return m
        }

    /**
     * @return the area associated with the bin, width*count
     */
    val binAreas: DoubleArray
        get() {
            val m = DoubleArray(bins.size)
            for ((index, bin) in bins.withIndex()) {
                m[index] = (bin.width * bin.count())
            }
            return m
        }

    /**
     *  A simple estimate of the "density" function
     *  for each bin using bin fraction/bin width values for each bin
     *  The bin width must be constant across the bins and not equal to 0.0
     */
    val densityEstimates: DoubleArray
        get() {
            val bws = binWidths
            require(bws.isAllEqual()) { "The width of each bin must be the same" }
            val bw = bws[0]
            require(bw > 0.0) { "The bin width must be > 0.0" }
            val m = DoubleArray(bins.size)
            val delta = count * bw
            for ((index, bin) in bins.withIndex()) {
                m[index] = bin.count() / delta
            }
            return m
        }

    /**
     * Returns the current bin count for the bin associated with x
     *
     * @param x the data to check
     * @return the bin count
     */
    fun binCount(x: Double): Double

    /**
     * Returns the bin count for the indicated bin
     *
     * @param binNum the bin number
     * @return the bin count for the indicated bin
     */
    fun binCount(binNum: Int): Double

    /**
     * Returns the fraction of the data relative to those
     * tabulated in the bins for the supplied bin number
     *
     * @param binNum the bin number
     * @return the fraction of the data
     */
    fun binFraction(binNum: Int): Double

    /**
     * Returns the fraction of the data relative to those
     * tabulated in the bins for the bin number associated with the x
     *
     * @param x the data point
     * @return the fraction
     */
    fun binFraction(x: Double): Double

    /**
     * Returns the cumulative count of all bins up to and
     * including the bin containing the value x
     *
     * @param x the data point
     * @return the cumulative bin count
     */
    fun cumulativeBinCount(x: Double): Double

    /**
     * Returns the cumulative count of all the bins up to
     * and including the indicated bin number
     *
     * @param binNum the bin number
     * @return cumulative count
     */
    fun cumulativeBinCount(binNum: Int): Double

    /**
     * Returns the cumulative fraction of the data up to and
     * including the indicated bin number
     *
     * @param binNum the bin number
     * @return the cumulative fraction
     */
    fun cumulativeBinFraction(binNum: Int): Double

    /**
     * Returns the cumulative fraction of the data up to and
     * including the bin containing the value of x
     *
     * @param x the datum
     * @return the cumulative fraction
     */
    fun cumulativeBinFraction(x: Double): Double

    /**
     * Returns the cumulative count of all the data (including underflow and overflow)
     * up to and including the indicated bin
     *
     * @param binNum the bin number
     * @return the cumulative count
     */
    fun cumulativeCount(binNum: Int): Double

    /**
     * Returns the cumulative count of all the data (including underflow
     * and overflow) for all bins up to and including the bin containing x
     *
     * @param x the datum
     * @return the cumulative count
     */
    fun cumulativeCount(x: Double): Double

    /**
     * Returns the cumulative fraction of all the data up to and including
     * the supplied bin (includes over and under flow)
     *
     * @param binNum the bin number
     * @return the cumulative fraction
     */
    fun cumulativeFraction(binNum: Int): Double

    /**
     * Returns the cumulative fraction of all the data up to an including
     * the bin containing the value x, (includes over and under flow)
     *
     * @param x the datum
     * @return the cumulative fraction
     */
    fun cumulativeFraction(x: Double): Double

    /**
     * Total number of observations collected including overflow and underflow
     *
     * @return Total number of observations
     */
    val totalCount: Double

    /**
     * The first bin's lower limit
     *
     * @return first bin's lower limit
     */
    val firstBinLowerLimit: Double

    /**
     * The last bin's upper limit
     *
     * @return last bin's upper limit
     */
    val lastBinUpperLimit: Double

    /**
     *  Returns the probability for each bin of the histogram based on an open
     *  integer range interpretation of the bin .
     *  The discrete distribution, [discreteCDF] must implement the ProbInRangeIfc interface
     */
    fun binProbabilities(discreteCDF : ProbInRangeIfc) : DoubleArray {
        val binProb = DoubleArray(numberBins)
        for((i, bin) in bins.withIndex()){
            binProb[i] = discreteCDF.probIn(bin.openIntRange)
        }
        return binProb
    }

    /**
     *  Returns the probability for each bin of the histogram based on a continuous interval
     *  interpretation of the bin .
     *  The distribution, [cdf] must implement the ContinuousDistributionIfc interface
     */
    fun binProbabilities(cdf : ContinuousDistributionIfc) : DoubleArray {
        val binProb = DoubleArray(numberBins)
        for((i, bin) in bins.withIndex()){
            binProb[i] = cdf.cdf(bin.interval)
        }
        return binProb
    }

    /**
     *  Returns the expected count for each bin of the histogram based on a continuous interval
     *  interpretation of the bin .
     *  The distribution, [cdf] must implement the ContinuousDistributionIfc interface
     */
    fun expectedCounts(cdf : ContinuousDistributionIfc): DoubleArray{
        return binProbabilities(cdf).multiplyConstant(count)
    }

    /**
     *  Creates a plot for the histogram. The parameter, [proportions]
     *  indicates whether proportions (true) or frequencies (false)
     *  will be shown on the plot. The default is true.
     */
    fun histogramPlot(proportions: Boolean = true) : HistogramPlot {
        return HistogramPlot(this, proportions)
    }
}