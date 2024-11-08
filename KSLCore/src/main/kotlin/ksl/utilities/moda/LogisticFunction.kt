package ksl.utilities.moda

import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Statistic
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 *  @param location the location of the distribution
 *  @param scale the scale of the distribution, must be greater than 0.0
 */
class LogisticFunction(
    var location: Double = 0.0,
    scale: Double = 1.0
) : ValueFunctionIfc {

    init {
        require(scale > 0.0) { "The scale must be > 0.0" }
    }

    /**
     *  @param pair is location, scale
     */
    constructor(pair: Pair<Double, Double>) : this(pair.first, pair.second)

    /**
     *  Estimates the location and scale from the data
     *  @param data there must be at least two observations
     */
    constructor(data: DoubleArray) : this(estimateLocationAndScale(data))

    var scale: Double = scale
        set(value) {
            require(scale > 0.0) { "The scale must be > 0.0" }
            field = value
        }

    override fun value(score: Score): Double {
        val z = (score.value - location) / scale
        var y = 1.0 / (1.0 + exp(-z))
        if (score.metric.direction == MetricIfc.Direction.SmallerIsBetter) {
            y = 1.0 - y
        }
        return y
    }

    companion object {

        /**
         *  Creates a useful logistic function based on the data based on using
         *  recommendLocationAndScale()
         */
        fun create(data: DoubleArray): LogisticFunction {
            return LogisticFunction(recommendLocationAndScale(data))
        }

        /**
         *  Estimates the location and scale based on the data
         *  The location is estimated by the median.
         *  The scale is estimated using the interquartile range.
         *  Wan, Xiang, Wenqian Wang, Jiming Liu, and Tiejun Tong. “Estimating the Sample Mean and Standard Deviation
         *  from the Sample Size, Median, Range and/or Interquartile Range.” BMC Medical Research Methodology 14, no. 1
         *  (December 2014): 135. https://doi.org/10.1186/1471-2288-14-135.
         *  @return the pair(location, scale)
         */
        fun estimateLocationAndScale(data: DoubleArray): Pair<Double, Double> {
            require(data.size >= 2) { "There must be at least two observations" }
            val b = BoxPlotSummary(data)
            val sd = Statistic.estimateStdDevFromIQR(b.interQuartileRange, b.count)
            val scale = sd * sqrt(3.0) / PI
            return Pair(b.median, scale)
        }

        /**
         *  Recommends the location and scale based on the data such that the scale is always between
         *  (factor*location, location*(1+factor)) based on the data.  If the estimated location and
         *  scale based on the data
         *  @param data the data used to estimate the location and scale
         *  @param factor a bounding factor to limit the scale to a useful range. Must be within (0,1)
         *  The default is 0.25.
         *  @return the pair(location, scale)
         */
        fun recommendLocationAndScale(data: DoubleArray, factor: Double = 0.25): Pair<Double, Double> {
            require((0.0 < factor) && (factor < 1.0)) { "The factor must be within (0,1)" }
            var (location, scale) = estimateLocationAndScale(data)
            val cvLL = location * factor
            val cvUL = location * (1.0 + factor)
            if (scale < cvLL) {
                scale = cvLL
            } else if (scale > cvUL) {
                scale = cvUL
            }
            return Pair(location, scale)
        }
    }
}