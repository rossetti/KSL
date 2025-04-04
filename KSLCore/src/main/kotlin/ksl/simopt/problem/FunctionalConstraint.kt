package ksl.simopt.problem

fun interface ConstraintFunctionIfc {

    fun lhs(inputs: InputMap): Double

}

class FunctionalConstraint(
    val validNames: Set<String>,
    val lhsFunc: ConstraintFunctionIfc,
    override val rhsValue: Double = 0.0,
    override val inequalityType: InequalityType = InequalityType.LESS_THAN
) : ConstraintIfc {

    init {
        require(validNames.isNotEmpty()){"The set of valid names must not be empty"}
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair.
     *  The names must be valid names for the equation.
     *  @return the total value representing the left-hand side of the constraint
     */
    override fun computeLHS(values: InputMap): Double {
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
    override fun isSatisfied(values: InputMap): Boolean {
        return computeLHS(values) < ltRHSValue
    }

}