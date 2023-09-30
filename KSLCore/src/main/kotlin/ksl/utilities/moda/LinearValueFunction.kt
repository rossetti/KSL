package ksl.utilities.moda

import ksl.utilities.Interval

class LinearValueFunction(
    metric: MetricIfc,
    range: Interval = Interval(0.0, 100.0)
) : ValueFunction(metric, range) {

    override fun value(x: Double): Double {
        // convert from incoming x to values using linear transformation
        val m = range.width / domain.width
        return m * (x - domain.lowerLimit) + range.lowerLimit
    }
}