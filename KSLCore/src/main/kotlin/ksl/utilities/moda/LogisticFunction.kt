package ksl.utilities.moda

import kotlin.math.exp

class LogisticFunction(
    val location: Double,
    val scale: Double
) : ValueFunctionIfc {

    init {
        require(scale > 0.0) {"The scale must be > 0.0"}
    }

    override fun value(score: Score): Double {
        val z = (score.value - location)/ scale
        var y = 1.0/(1.0 + exp(-z))
        if (score.metric.direction == MetricIfc.Direction.SmallerIsBetter) {
            y = 1.0 - y
        }
        return y
    }
}