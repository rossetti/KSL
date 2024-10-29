package ksl.utilities.moda

import kotlin.math.exp

class LogisticFunction(
    var location: Double = 0.0,
    scale: Double = 1.0
) : ValueFunctionIfc {

    init {
        require(scale > 0.0) {"The scale must be > 0.0"}
    }

    var scale: Double = scale
        set(value) {
            require(scale > 0.0) {"The scale must be > 0.0"}
            field = value
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