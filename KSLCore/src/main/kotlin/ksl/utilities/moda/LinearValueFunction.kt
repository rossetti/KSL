package ksl.utilities.moda

class LinearValueFunction(metric: MetricIfc) : ValueFunction(metric) {

    override fun value(x: Double): Double {
        // convert from incoming x to values using linear transformation
        val m = range.width / domain.width
        return m * (x - domain.lowerLimit) + range.lowerLimit
    }
}