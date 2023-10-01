package ksl.utilities.moda

/**
 *  Uses a linear transformation to transform from metric domain
 *  to the value domain.
 */
class LinearValueFunction(
    metric: MetricIfc,
) : ValueFunction(metric) {

    override fun value(x: Double): Double {
        require(metric.domain.contains(x)) {"The value x = $x is not within domain = ${metric.domain}"}
        // convert from incoming x to [0,1] values using a linear transformation
        val domain = metric.domain
        var y = (x - domain.lowerLimit)/ domain.width
        if (metric.direction == MetricIfc.Direction.SmallerIsBetter){
            y = 1.0 - y
        }
        return y
    }
}