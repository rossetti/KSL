package ksl.utilities.moda

import ksl.utilities.Interval

interface ValueFunctionIfc {

    val domain: Interval
    val range: Interval

    fun value(x: Double): Double

}

abstract class ValueFunction(
    protected val myMetric: MetricIfc,
    override val range: Interval = Interval(0.0, 100.0)
) : ValueFunctionIfc {

    init {
        require(myMetric.domain.width > 0.0) { "The width of the domain must be > 0.0" }
        require(myMetric.domain.width.isFinite()) { "The width of the domain must be finite." }
        require(myMetric.domain.upperLimit.isFinite()) { "The upper limit of the domain of x must be finite. It was ${myMetric.domain.upperLimit}" }
        require(range.width.isFinite()) {"The width of the range must be finite"}
        require(range.width > 0.0) {"The width of the range must be > 0.0"}
    }

    override val domain: Interval
        get() = myMetric.domain

}