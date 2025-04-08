package ksl.simopt.problem

/**
 *  A response constraint represents a general constrain of the form E[G(x)] < c or E[G(x)] > c
 *  where G(x) is some response from the model that is a function of the model inputs.
 *
 *  @param responseName the name of the response in the model
 *  @param rhsValue the right-hand side value
 *  @param target the constraint's target. A parameter often used by solver methods that behaves
 *  as a cut-off point between desirable and unacceptable systems
 *  @param tolerance the constraint's tolerance. A parameter often used by solver methods that
 *  specifies how much we are willing to be off from the target. Similar to an indifference parameter.
 *  @param inequalityType the type of inequality (less-than or greater-than)
 */
class ResponseConstraint(
    val responseName: String,
    var rhsValue: Double,
    val target: Double = 0.0,
    val tolerance: Double = 0.0,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
) {
    init {
        require(responseName.isNotBlank()) { "The response name cannot be blank" }
    }

    private val inequalityFactor: Double = if (inequalityType == InequalityType.LESS_THAN) 1.0 else -1.0

    val ltRHSValue: Double
        get() = inequalityFactor * rhsValue

    /**
     *  The "slack" is the gap between the response and the right-hand side of the constraint adjusted
     *  to ensure a less-than interpretation.  If R(x) is the estimated response value, then for a less-than
     *  constraint, where E[R(x)] < b, then the slack is b-R(x).  Essentially, the slack is (b-R(x)) adjusted
     *  for the direction of the constraint.
     *  @param responseValue The value of the response to be evaluated for the constraint.
     */
    fun slack(responseValue: Double): Double {
        return inequalityFactor * (rhsValue - responseValue)
    }

    /**
     *  The violation is maxOf(0, R(x) - b). This quantity is often used in penalty function calculations.
     *  @param responseValue The value of the response to be evaluated for the constraint.
     */
    fun violation(responseValue: Double): Double {
        return maxOf(0.0, -slack(responseValue))
    }

}
