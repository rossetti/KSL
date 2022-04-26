package ksl.utilities.statistic

import ksl.utilities.IdentityIfc
import ksl.utilities.KSLArrays
import ksl.utilities.math.KSLMath
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

    companion object {
        /**
         * Create a histogram with lower limit set to zero
         *
         * @param upperLimit the upper limit of the last bin, cannot be positive infinity
         * @param numBins    the number of bins to create, must be greater than 0
         * @return the histogram
         */
        fun create(upperLimit: Double, numBins: Int): HistogramIfc {
            return create(0.0, upperLimit, numBins, null)
        }

        /**
         * Create a histogram
         *
         * @param lowerLimit lower limit of first bin, cannot be negative infinity
         * @param upperLimit the upper limit of the last bin, cannot be positive infinity
         * @param numBins    the number of bins to create, must be greater than 0
         * @return the histogram
         */
        fun create(lowerLimit: Double, upperLimit: Double, numBins: Int): HistogramIfc {
            return create(lowerLimit, upperLimit, numBins, null)
        }

        /**
         * Create a histogram with the given name based on the provided values
         *
         * @param lowerLimit lower limit of first bin, cannot be negative infinity
         * @param upperLimit the upper limit of the last bin, cannot be positive infinity
         * @param numBins    the number of bins to create, must be greater than zero
         * @param name       the name of the histogram
         * @return the histogram
         */
        fun create(lowerLimit: Double, upperLimit: Double, numBins: Int, name: String?): HistogramIfc {
            return HistogramB(createBreakPoints(lowerLimit, upperLimit, numBins))
        }

        /**
         * @param numBins    the number of bins to make, must be greater than zero
         * @param lowerLimit the lower limit of the first bin, cannot be negative infinity
         * @param width      the width of each bin, must be greater than zero
         * @return the created histogram
         */
        fun create(lowerLimit: Double, numBins: Int, width: Double): HistogramIfc {
            return HistogramB(createBreakPoints(lowerLimit, numBins, width))
        }

        /**
         * Divides the range equally across the number of bins.
         *
         * @param lowerLimit lower limit of first bin, cannot be negative infinity
         * @param upperLimit the upper limit of the last bin, cannot be positive infinity
         * @param numBins    the number of bins to create, must be greater than zero
         * @return the break points
         */
        fun createBreakPoints(lowerLimit: Double, upperLimit: Double, numBins: Int): DoubleArray {
            require(!lowerLimit.isInfinite()) { "The lower limit of the range cannot be infinite." }
            require(!upperLimit.isInfinite()) { "The upper limit of the range cannot be infinite." }
            require(lowerLimit < upperLimit) { "The lower limit must be < the upper limit of the range" }
            require(numBins > 0) { "The number of bins must be > 0" }
            val binWidth = KSLMath.roundToScale((upperLimit - lowerLimit) / numBins, false)
            return createBreakPoints(lowerLimit, numBins, binWidth)
        }

        /**
         * @param numBins    the number of bins to make, must be greater than 0
         * @param lowerLimit the lower limit of the first bin, cannot be negative infinity
         * @param width      the width of each bin, must be greater than 0
         * @return the constructed break points
         */
        fun createBreakPoints(lowerLimit: Double, numBins: Int, width: Double): DoubleArray {
            require(!lowerLimit.isInfinite()) { "The lower limit of the range cannot be infinite." }
            require(numBins > 0) { "The number of bins must be > 0" }
            require(width > 0) { "The width of the bins must be > 0" }
            val points = DoubleArray(numBins + 1)
            points[0] = lowerLimit
            for (i in 1 until points.size) {
                points[i] = points[i - 1] + width
            }
            return points
        }

        /**
         * @param breakPoints the break points w/o negative infinity
         * @return the break points with Double.NEGATIVE_INFINITY as the first break point
         */
        fun addNegativeInfinity(breakPoints: DoubleArray): DoubleArray {
            require(breakPoints.isNotEmpty()) { "The break points array was empty" }
            val b = DoubleArray(breakPoints.size + 1)
            System.arraycopy(breakPoints, 0, b, 1, breakPoints.size)
            b[0] = kotlin.Double.NEGATIVE_INFINITY
            return b
        }

        /**
         * @param breakPoints the break points w/o positive infinity
         * @return the break points with Double.POSITIVE_INFINITY as the last break point
         */
        fun addPositiveInfinity(breakPoints: DoubleArray): DoubleArray {
            require(breakPoints.isNotEmpty()) { "The break points array was empty" }
            val b = breakPoints.copyOf(breakPoints.size + 1)
            b[b.size - 1] = Double.POSITIVE_INFINITY
            return b
        }

        /**
         * http://www.fmrib.ox.ac.uk/analysis/techrep/tr00mj2/tr00mj2/node24.html
         *
         * @param observations observations for a histogram
         * @return a set of break points based on some theory
         */
        fun recommendBreakPoints(observations: DoubleArray): DoubleArray {
            require(observations.isNotEmpty()) { "The supplied observations array was empty" }
            if (observations.size == 1) {
                // use the sole observation
                val b = DoubleArray(1)
                b[0] = floor(observations[0])
                return b
            }
            // 2 or more observations
            val statistic = Statistic(observations)
            val LL = statistic.min
            val UL = statistic.max
            if (KSLMath.equal(LL, UL)) {
                // essentially the same, go back to 1 observation
                val b = DoubleArray(1)
                b[0] = floor(LL)
                return b
            }
            // more than 2 and some spread
            // try to approximate a reasonable number of bins from the observations
            // first determine a reasonable bin width
            val s = statistic.standardDeviation
            val n = statistic.count
            // http://www.fmrib.ox.ac.uk/analysis/techrep/tr00mj2/tr00mj2/node24.html
            //double iqr = 1.35*s;
            // use the more "optimal" estimate
            val width = 3.49 * s * n.pow(-1.0 / 3.0)
            // round the width to a reasonable scale
            val binWidth = KSLMath.roundToScale(width, false)
            // now compute a number of bins for this width
            val nb = (ceil(UL) - floor(LL)) / binWidth
            val numBins = ceil(nb).toInt()
            return createBreakPoints(floor(LL), numBins, binWidth)
        }

        /** Creates a list of ordered bins for use in a histogram
         *
         * @param breakPoints the break points
         * @return the list of histogram bins
         */
        fun makeBins(breakPoints: DoubleArray): List<HistogramBin> {
            require(KSLArrays.isStrictlyIncreasing(breakPoints)) { "The break points were not strictly increasing." }
            val binList: MutableList<HistogramBin> = ArrayList()
            // case of 1 break point must be handled
            if (breakPoints.size == 1) {
                // two bins, 1 below and 1 above
                binList.add(HistogramBin(1, Double.NEGATIVE_INFINITY, breakPoints[0]))
                binList.add(HistogramBin(2, breakPoints[0], Double.POSITIVE_INFINITY))
                return binList
            }

            // two or more break points
            for (i in 1 until breakPoints.size) {
                binList.add(HistogramBin(i, breakPoints[i - 1], breakPoints[i]))
            }
            return binList
        }
    }
}