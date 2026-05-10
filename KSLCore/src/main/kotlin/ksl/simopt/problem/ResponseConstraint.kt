package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse
import ksl.utilities.Interval
import ksl.utilities.distributions.StudentT

/**
 *  A response constraint represents a general constraint of the form E[R(x)] < b or E[R(x)] > b
 *  where R(x) is some response from the model that is a function of the model inputs.
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
    val rhsValue: Double,
    val inequalityType: InequalityType = InequalityType.LESS_THAN,
    val target: Double = 0.0,
    val tolerance: Double = 0.0,
    val penaltyFunction: PenaltyFunctionIfc? = null
) {
    init {
        require(responseName.isNotBlank()) { "The response name cannot be blank" }
        require(!rhsValue.isNaN()) {"The right-hand side value cannot be NaN"}
        require(rhsValue.isFinite()) {"The right-hand side value must be finite."}
        require(target >= 0.0) { "The target must be >= 0.0." }
        require(tolerance >= 0.0) { "The tolerance must be >= 0.0." }
    }

    /**
     *  The string for inequality either <= or >=
     */
    val inequalityString: String
        get() = if (inequalityType == InequalityType.LESS_THAN) "<=" else ">="

    override fun toString(): String {
        return "Response Constraint: $responseName $inequalityString $rhsValue (Target: $target, Tolerance: $tolerance)"
    }

    /**
     * Evaluates the response constraint against the provided simulation estimate
     * and generates a formatted string reporting the result and statistics.
     *
     * @param estResponse The estimated response from the simulation.
     * @return A formatted report string.
     */
    fun resultsAsString(estResponse: EstimatedResponse): String {
        // Ensure we are evaluating the correct response
        require(estResponse.name == responseName) {
            "Supplied response name '${estResponse.name}' does not match constraint '$responseName'"
        }

        val lhs = estResponse.average
        val v = violation(estResponse.average)

        val status = if (v > 0.0) "[VIOLATED] " else "[SATISFIED]"
        val lhsStr = String.format("%10.4f", lhs)
        val rhsStr = String.format("%10.4f", rhsValue)
        val violStr = String.format("%10.4f", v)

        // --- SMART FORMATTING FOR STANDARD ERROR ---
        val se = estResponse.standardError
        val seStr = if (se > 0.0 && se < 0.0001) {
            String.format("%8.2e", se)
        } else {
            String.format("%8.4f", se)
        }

        // --- SMART FORMATTING FOR HALF-WIDTH ---
        // Assuming halfWidth() requires the estResponse as an argument.
        // If your halfWidth() function takes no arguments, change this to just halfWidth()
        val hw = estResponse.halfWidth()
        val hwStr = if (hw > 0.0 && hw < 0.0001) {
            String.format("%8.2e", hw)
        } else {
            String.format("%8.4f", hw)
        }

        // Pad the response name to 15 characters so columns align across different constraints
        val nameStr = responseName.padEnd(15)

        return "  $status $nameStr | LHS: $lhsStr $inequalityString RHS: $rhsStr | Viol: $violStr | (N=${estResponse.count}, SE=$seStr, 95% HW=$hwStr)"
    }

    /**
     *  If supplied will be used to estimate if the constraint is satisfied.
     */
    var feasibilityChecker: FeasibilityCheckerIfc? = null

    private val inequalityFactor: Double = if (inequalityType == InequalityType.LESS_THAN) 1.0 else -1.0

    @Suppress("unused")
    val targetInterval: Interval
        get() = Interval(rhsValue - target, rhsValue + target)

    @Suppress("unused")
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
//        println("responseName = $responseName")
//        println("response value = $responseValue")
//        println("rhsValue = $rhsValue")
//        println("inequalityFactor = $inequalityFactor")
//        println("rhsValue - responseValue = ${rhsValue - responseValue}")
//        println("-slack(responseValue) = ${-slack(responseValue)}")
        return maxOf(0.0, -slack(responseValue))
    }

    /**
     * Overloaded violation function that accepts an EstimatedResponse.
     */
    fun violation(estimatedResponse: EstimatedResponse): Double {
        require(estimatedResponse.name == responseName) {
            "Supplied response name '${estimatedResponse.name}' does not match constraint '$responseName'"
        }
        // Delegate the math to your existing violation(Double) function
        return violation(estimatedResponse.average)
    }

    /**
     *  Returns true if the implied test of feasibility indicates that the constraint is satisfied. If the
     *  user supplied a FeasibilityCheckerIfc instance for the property feasibilityChecker, it is used to
     *  check for feasibility; otherwise, the function uses the function oneSidedUpperResponseInterval()
     *  to construct a one-sided upper confidence interval for the constraint and uses the interval to check for
     *  feasibility.
     *
     *  @param estimatedResponse the supplied response. It must have the same name as the response associated with
     * the constraint and the number of observations (count) must be greater than or equal to 2.
     *  @param confidenceLevel the confidence level for computing the upper limit of the confidence interval
     *  @return true if the test for feasibility passes otherwise false
     */
    fun testFeasibility(
        estimatedResponse: EstimatedResponse,
        confidenceLevel: Double
    ) : Boolean {
        return feasibilityChecker?.isFeasible(this, estimatedResponse,
            confidenceLevel) ?: (oneSidedUpperResponseInterval(estimatedResponse, confidenceLevel).upperLimit <= 0.0)
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
