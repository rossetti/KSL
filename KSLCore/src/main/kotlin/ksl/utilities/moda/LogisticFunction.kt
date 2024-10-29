package ksl.utilities.moda

import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Statistic
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

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
    }
}