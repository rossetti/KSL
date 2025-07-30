package ksl.simopt.problem

/**
 * Represents a functional constraint in an optimization problem.
 *
 * This class models a constraint where the left-hand side is computed
 * using a user-defined function and is compared to a predefined right-hand side value
 * using an inequality (less than or greater than).
 *
 * The valid variable names are defined at the time of initialization,
 * and the left-hand side function is applied to input values to compute
 * the constraint's value.
 *
 * @param validNames the input variable names that are valid for the function. The list
 * must not be empty and must contain non-blank strings.
 * @property lhsFunc The function used to compute the left-hand side of the equation.
 * @property rhsValue The right-hand side value of the constraint. Defaults to 0.0.
 * @property inequalityType The type of inequality (LESS_THAN or GREATER_THAN). Defaults to LESS_THAN.
 */
class FunctionalConstraint(
    validNames: List<String>,
    val lhsFunc: ConstraintFunctionIfc,
    override val rhsValue: Double = 0.0,
    override val inequalityType: InequalityType = InequalityType.LESS_THAN
) : ConstraintIfc {

    val validNames: List<String>

    init {
        require(validNames.isNotEmpty()){"The list of valid names must not be empty"}
        for (name: String in validNames) {
            require(name.isNotBlank()) { "The name was blank" }
        }
        this.validNames = validNames.distinct()
    }

    override fun toString() : String {
        val sb = StringBuilder().apply{
            append("Equation: ")
            val names = validNames.joinToString(", ")
            append("f($names)")
            if (inequalityType == InequalityType.LESS_THAN) {
                append(" <= ")
            } else {
                append(" >= ")
            }
            append("$rhsValue ")
        }
        return sb.toString()
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair.
     *  The names must be valid names for the equation.
     *  @return the total value representing the left-hand side of the constraint
     */
    override fun computeLHS(values: Map<String, Double>): Double {
        require(ProblemDefinition.validate(values, validNames)) {"The names of the input variables should be valid names for the problem"}
        return lhsFunc.lhs(values) * inequalityFactor
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation and checks if the constraint is satisfied.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair
     *  @return true if the constraint is satisfied, false otherwise.
     */
    override fun isSatisfied(values: Map<String, Double>): Boolean {
        return computeLHS(values) < ltRHSValue
    }

    fun computeLHS(values: DoubleArray): Double {

    }

}