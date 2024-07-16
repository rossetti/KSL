package ksl.utilities.moda

/**
 *  Uses a linear transformation to transform from score with a metric domain
 *  to the value domain.
 */
class LinearValueFunction() : ValueFunctionIfc {

    override fun value(score: Score): Double {
        val domain = score.metric.domain
        var y = (score.value - domain.lowerLimit) / domain.width
        if (score.metric.direction == MetricIfc.Direction.SmallerIsBetter) {
            y = 1.0 - y
        }
        return y
    }

}