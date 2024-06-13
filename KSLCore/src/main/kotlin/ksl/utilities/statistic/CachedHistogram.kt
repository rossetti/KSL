package ksl.utilities.statistic

import ksl.utilities.DoubleArraySaver
import ksl.utilities.random.rvariable.NormalRV

/**
 *  Creates a dynamically configured histogram based on an observed cache.
 *   If the amount of data observed is less than cache size and greater
 *   than or equal to 2, the returned histogram will be configured on whatever data
 *   was available in the cache. Thus, bin settings may change as more
 *   data is collected until the cache is full. Once the cache is full the returned histogram
 *   is permanently configured based on all data in the cache.
 *   The default cache size [cacheSize] is 512 observations.
 */
class CachedHistogram(
    val cacheSize: Int = 512,
    name: String? = null,
) : AbstractStatistic(name), HistogramIfc {

    /** If the size of the data array is less than the cache size and greater than or equal to 2,
     * the created histogram will be configured based on whatever data was supplied. However,
     * the bins may change if additional data is collected until the cache is full. Once
     * the cache is full, the returned histogram is permanently configured based on the defined
     * cache size [cacheSize].
     *
     * @param data an array of observations to use for the histogram
     * @param cacheSize the size of the cache used to configure the bins of the histogram. The default is 512.
     * @param name an optional name for the histogram
     */
    constructor(data: DoubleArray, cacheSize: Int = 512, name: String? = null) : this(cacheSize, name) {
        collect(data)
    }

    private val myCache: DoubleArraySaver = DoubleArraySaver()

    val cacheData: DoubleArray
        get() = myCache.savedData()

    private var myHistogram: Histogram = Histogram(doubleArrayOf(0.0), this.name)

    /**
     *  Returns a histogram configured with break points based on the cached observed
     *  data.  If the amount of data observed is less than cache size and greater
     *  than or equal to 2, the returned histogram was configured on whatever data
     *  was available in the cache. Thus, bin tabulation may change as more
     *  data is collected until the cache is full. Then the returned histogram
     *  is permanently configured based on all data in the cache.
     */
    val currentHistogram: Histogram
        get() {
            return if (myHistogram.count <= 1.0) {
                myHistogram
            } else if ((2 <= myHistogram.count) && (myHistogram.count < cacheSize)) {
                Histogram.create(myCache.savedData())
            } else {
                myHistogram
            }
        }
    override val binArray: Array<HistogramBin>
        get() = currentHistogram.binArray
    override val binCounts: DoubleArray
        get() = currentHistogram.binCounts
    override val bins: List<HistogramBin>
        get() = currentHistogram.bins
    override val breakPoints: DoubleArray
        get() = currentHistogram.breakPoints

    override val firstBinLowerLimit: Double
        get() = currentHistogram.firstBinLowerLimit

    override val lastBinUpperLimit: Double
        get() = currentHistogram.lastBinUpperLimit

    override val numberBins: Int
        get() = currentHistogram.numberBins

    override val overFlowCount: Double
        get() = currentHistogram.overFlowCount

    override val totalCount: Double
        get() = currentHistogram.totalCount
    override val underFlowCount: Double
        get() = currentHistogram.underFlowCount

    override val average: Double
        get() = currentHistogram.average

    override val count: Double
        get() = currentHistogram.count
    override val deviationSumOfSquares: Double
        get() = currentHistogram.deviationSumOfSquares

    override val kurtosis: Double
        get() = currentHistogram.kurtosis

    override val lag1Correlation: Double
        get() = currentHistogram.lag1Correlation
    override val lag1Covariance: Double
        get() = currentHistogram.lag1Covariance
    override val max: Double
        get() = currentHistogram.max
    override val min: Double
        get() = currentHistogram.min

    override val negativeCount: Double
        get() = currentHistogram.negativeCount

    override val skewness: Double
        get() = currentHistogram.skewness
    override val standardError: Double
        get() = currentHistogram.standardError
    override val sum: Double
        get() = currentHistogram.sum

    override val variance: Double
        get() = currentHistogram.variance
    override val vonNeumannLag1TestStatistic: Double
        get() = currentHistogram.vonNeumannLag1TestStatistic
    override val zeroCount: Double
        get() = currentHistogram.zeroCount

    override fun bin(x: Double): HistogramBin {
        return currentHistogram.bin(x)
    }

    override fun bin(binNum: Int): HistogramBin {
        return currentHistogram.bin(binNum)
    }

    override fun binCount(x: Double): Double {
        return currentHistogram.binCount(x)
    }

    override fun binCount(binNum: Int): Double {
        return currentHistogram.binCount(binNum)
    }

    override fun binFraction(x: Double): Double {
        return currentHistogram.binFraction(x)
    }

    override fun binFraction(binNum: Int): Double {
        return currentHistogram.binFraction(binNum)
    }

    override fun binNumber(x: Double): Int {
        return currentHistogram.binNumber(x)
    }

    override fun cumulativeBinCount(x: Double): Double {
        return currentHistogram.cumulativeBinCount(x)
    }

    override fun cumulativeBinCount(binNum: Int): Double {
        return currentHistogram.cumulativeBinCount(binNum)
    }

    override fun cumulativeBinFraction(x: Double): Double {
        return currentHistogram.cumulativeBinFraction(x)
    }

    override fun cumulativeBinFraction(binNum: Int): Double {
        return currentHistogram.cumulativeBinFraction(binNum)
    }

    override fun cumulativeCount(x: Double): Double {
        return currentHistogram.cumulativeCount(x)
    }

    override fun cumulativeCount(binNum: Int): Double {
        return currentHistogram.cumulativeCount(binNum)
    }

    override fun cumulativeFraction(x: Double): Double {
        return currentHistogram.cumulativeFraction(x)
    }

    override fun cumulativeFraction(binNum: Int): Double {
        return currentHistogram.cumulativeFraction(binNum)
    }

    override fun findBin(x: Double): HistogramBin {
        return currentHistogram.findBin(x)
    }

    override fun halfWidth(level: Double): Double {
        return currentHistogram.halfWidth(level)
    }

    override fun leadingDigitRule(multiplier: Double): Int {
        return currentHistogram.leadingDigitRule(multiplier)
    }

    /**
     *  After reset, it will be as if no data had been observed.
     */
    override fun reset() {
        super.reset()
        myHistogram.reset()
        myCache.clearData()
        myCache.saveOption = true
    }

    override fun collect(obs: Double) {
        myCache.save(obs)
        myHistogram.collect(obs)
        if (myCache.saveOption) {
            if (myCache.saveCount == cacheSize) {
                //  turn off saving
                myCache.saveOption = false
                // replace the current histogram with the one based on the saved data
                myHistogram = Histogram.create(myCache.savedData(), name = this.name)
            }
        }
    }

    override fun toString(): String {
        return currentHistogram.toString()
    }
}