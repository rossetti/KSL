package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse
import ksl.utilities.Interval
import ksl.utilities.distributions.StudentT

/**
 *  A response constraint represents a general constrain of the form E[G(x)] < c or E[G(x)] > c
 *  where G(x) is some response from the model that is a function of the model inputs.
 *
 *  @param responseName the name of the response in the model
 *  @param rhsValue the right-hand side value
 *  @param inequalityType the type of inequality (less-than or greater-than)
 *  @param target the constraint's target. A parameter often used by solver methods that behaves
 *  as a cut-off point between desirable and unacceptable systems
 *  @param tolerance the constraint's tolerance. A parameter often used by solver methods that
 *  specifies how much we are willing to be off from the target. Similar to an indifference parameter.
 */
class ResponseConstraint(
    val responseName: String,
    var rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    val target: Double = 0.0,
    val tolerance: Double = 0.0
) {
    init {
        require(responseName.isNotBlank()) { "The response name cannot be blank" }
        require(target >= 0.0) { "The target must be >= 0.0." }
        require(tolerance >= 0.0) { "The tolerance must be >= 0.0." }
    }

    private val inequalityFactor: Double = if (inequalityType == InequalityType.LESS_THAN) 1.0 else -1.0

    val targetInterval: Interval
        get() = Interval(rhsValue - target, rhsValue + target)

    val toleranceInterval: Interval
        get() = Interval(rhsValue - tolerance, rhsValue + tolerance)

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
     *  The difference between the response and the right-hand side of the constraint adjusted to ensure
     *  a less-than interpretation. If R(x) is the estimated response value, then for a less-than
     *  constraint, where E[R(x)] < b, then the difference is R(x) - b.  This difference is useful
     *  when constructing confidence intervals.
     */
    fun difference(responseValue: Double): Double {
        return inequalityFactor * (responseValue - rhsValue)
    }

    /**
     *  The violation is maxOf(0, R(x) - b). This quantity is often used in penalty function calculations.
     *  @param responseValue The value of the response to be evaluated for the constraint.
     */
    fun violation(responseValue: Double): Double {
        return maxOf(0.0, -slack(responseValue))
    }

    /**
     *  Computes a one-sided upper confidence interval for the response constraint to test
     *  if the interval contains zero. The interval is based on the estimated difference between the value
     *  of the response and the right-hand side constraint value. If the constraint has form E[R(x)] < b,
     *  then if E[R(x)] - b < 0, the constraint is satisfied.  If R is an estimated value of E[R(x)], then
     *  the difference estimate is D = R - b.  The computed confidence interval is a one-sided upper
     *  confidence interval on the difference. If the interval contains 0, then we do not have
     *  enough evidence to conclude that the constraint is satisfied.
     *
     *  If the upper limit of the interval is less than 0.0, then we can be confident
     *  that response constraint maybe feasible. The construction of the interval assumes that the supplied
     *  response is normally distributed.
     *
     * @param estimatedResponse the supplied response. It must have the same name as the response associated with
     * the constraint and the number of observations (count) must be greater than or equal to 2.
     *  @param confidenceLevel the confidence level for computing the upper limit of the confidence interval
     *  @return the construction interval. By construction, the lower limit will be negative infinity.
     */
    fun oneSidedUpperResponseInterval(
        estimatedResponse: EstimatedResponse,
        confidenceLevel: Double
    ): Interval {
        require(estimatedResponse.name == responseName) { "The supplied response name was not the same as $responseName" }
        require(!(confidenceLevel <= 0.0 || confidenceLevel >= 1.0)) { "Confidence Level must be (0,1)" }
        require(estimatedResponse.count >= 2) { "The estimated response count must be at least 2" }
        val d = difference(estimatedResponse.average)
        val dof = estimatedResponse.count - 1.0
        val t = StudentT.invCDF(dof, confidenceLevel)
        val c = t * estimatedResponse.standardError
        val ul = d + c
        return Interval(Double.NEGATIVE_INFINITY, ul)
    }

}
