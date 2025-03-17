package ksl.simopt.problem

import ksl.utilities.Interval

data class InputDefinition(
    val name: String,
    val lowerBound: Double,
    val upperBound: Double,
    val granularity: Double = 0.0
){
    init {
        require(name.isNotBlank()) { "name cannot be blank" }
        require(lowerBound < upperBound) { "lower bound must be less than upper bound" }
        require(granularity >= 0.0) { "granularity must be >=  0.0" }
    }

    constructor(
        name: String,
        interval: Interval,
        granularity: Double
    ) : this(name, interval.lowerLimit, interval.upperLimit, granularity)

}
