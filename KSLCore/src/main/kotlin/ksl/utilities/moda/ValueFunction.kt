package ksl.utilities.moda

import ksl.utilities.Interval

interface ValueFunctionIfc {
    val metric: MetricIfc
    val domain: Interval
    val range: Interval

    fun value(x: Double): Double

}

abstract class ValueFunction(
    override val metric: MetricIfc,
    override val range: Interval
) : ValueFunctionIfc {

    init {
        require(metric.domain.width > 0.0) { "The width of the domain must be > 0.0" }
        require(metric.domain.width.isFinite()) { "The width of the domain must be finite." }
        require(metric.domain.upperLimit.isFinite()) { "The upper limit of the domain of x must be finite. It was ${metric.domain.upperLimit}" }
        require(range.width.isFinite()) {"The width of the range must be finite"}
        require(range.width > 0.0) {"The width of the range must be > 0.0"}
    }

    override val domain: Interval
        get() = metric.domain

}