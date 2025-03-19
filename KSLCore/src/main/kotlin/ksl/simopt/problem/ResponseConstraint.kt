package ksl.simopt.problem

import kotlin.math.pow

class ResponseConstraint(
    val name: String,
    var rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    violationPenalty: Double = 1000.0,
    violationExponent: Double = 2.0
) {
    init {
        require(violationPenalty >= 0.0) { "The violationPenalty must be >= 0.0" }
        require(violationExponent >= 1.0) { "The violationExponent must be >= 1.0" }
        require(name.isNotBlank()) { "The response name cannot be blank" }
    }

    var violationPenalty: Double = violationPenalty
        set(value) {
            require(value >= 0.0) { "The violationPenalty must be >= 0.0" }
            field = value
        }

    var violationExponent: Double = violationExponent
        set(value) {
            require(value >= 1.0) { "The violationExponent must be >= 0.0" }
            field = value
        }

    private val inequalityFactor: Double = if (inequalityType == InequalityType.LESS_THAN) 1.0 else -1.0

    val ltRHSValue: Double
        get() = inequalityFactor * rhsValue

    fun slack(v: Double): Double {
        return inequalityFactor * (rhsValue - v)
    }

    fun violation(v: Double): Double {
        return maxOf(0.0, -slack(v))
    }

    fun penalty(v: Double): Double {
        return violation(v) * violationPenalty.pow(violationExponent)
    }
}
