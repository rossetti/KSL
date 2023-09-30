package ksl.utilities.moda

import ksl.utilities.Interval

class LinearValueFunction(
    metric: MetricIfc,
    valueRange: Interval
) : ValueFunction(metric, valueRange) {

    override fun value(x: Double): Double {
        // convert from incoming x to values using linear transformation
        val m = range.width / domain.width
        var y = m * (x - domain.lowerLimit) + range.lowerLimit
        if (metric.direction == MetricIfc.Direction.SmallerIsBetter){
            y = range.upperLimit - y
        }
        return y
    }
}