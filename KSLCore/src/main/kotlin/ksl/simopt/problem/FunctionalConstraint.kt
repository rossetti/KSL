package ksl.simopt.problem

fun interface ConstraintFunctionIfc {

    fun lhs(inputs: Map<String, Double>): Double

    fun validate(inputs: Map<String, Double>, names: Set<String>): Boolean {
        for ((name, _) in inputs) {
            if (!names.contains(name)) return false
        }
        return true
    }
}

class FunctionalConstraint(
    val lhsFunc: ConstraintFunctionIfc,
    override val rhsValue: Double = 0.0,
    override val inequalityType: InequalityType = InequalityType.LESS_THAN
) : ConstraintIfc {

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair.
     *  The names must be in the equation.
     *  @return the total value representing the left-hand side of the linear equation
     */
    override fun computeLHS(values: Map<String, Double>): Double {
        return lhsFunc.lhs(values)
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

}