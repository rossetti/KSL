package ksl.simopt.problem

import kotlin.math.pow

/**
 *  A response constraint represents a general constrain of the form E[G(x)] < c or E[G(x)] > c
 *  where G(x) is some response from the model that is a function of the model inputs.
 *
 *  @param responseName the name of the response in the model
 *  @param rhsValue the right-hand side value
 *  @param inequalityType the type of inequality (less-than or greater-than)
 *  @param violationPenalty the base penalty to apply to violating the constraint. The
 *  default is 1000.0
 *  @param violationExponent the exponent used to magnify the penalty. The default is 2.0.
 */
class ResponseConstraint(
    val responseName: String,
    var rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    violationPenalty: Double = 1000.0,
    violationExponent: Double = 2.0
) {
    init {
        require(violationPenalty >= 0.0) { "The violationPenalty must be >= 0.0" }
        require(violationExponent >= 1.0) { "The violationExponent must be >= 1.0" }
        require(responseName.isNotBlank()) { "The response name cannot be blank" }
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

    //TODO need to think about violations and penalties

    fun violation(v: Double): Double {
        return maxOf(0.0, -slack(v))
    }

    fun penalty(v: Double): Double {
        return violation(v) * violationPenalty.pow(violationExponent)
    }
}
