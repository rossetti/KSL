package ksl.simopt.problem

interface ConstraintIfc {

    /**
     *  The type of inequality less than or greater than
     */
    val inequalityType: InequalityType

    /**
     *  Used to turn greater than inequalities to less than inequalities
     */
    val inequalityFactor: Double
        get() = if (inequalityType == InequalityType.LESS_THAN) 1.0 else -1.0

    /**
     *  The right-hand side of the constraint adjusted for the direction of the inequality to ensure
     *  a less-than constraint
     */
    val ltRHSValue: Double
        get() = inequalityFactor * rhsValue

    /**
     *  The right-hand side of the constraint
     */
    val rhsValue: Double

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation.  The calculation should take into
     *  account the direction of the inequality such that the inequality is interpreted as less than.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair.
     *  The names must be in the equation.
     *  @return the total value representing the left-hand side of the linear equation
     */
    fun computeLHS(values: Map<String, Double>): Double

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation and checks if the constraint is satisfied.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair
     *  @return true if the constraint is satisfied, false otherwise.
     */
    fun isSatisfied(values: Map<String, Double>): Boolean

    /**
     *  The slack associated with the constraint based on the provided inputs.
     *  This is the difference between the right-hand side value and the left-hand side value.
     *  @param inputs the map containing the input variable name and the current value of the input variable as a pair.
     *  The names must be in the equation.
     *  @return the difference between the right-hand side value and the left-hand side value.
     */
    @Suppress("unused")
    fun slack(inputs: Map<String, Double>): Double {
        return ltRHSValue - computeLHS(inputs)
    }

    /**
     *  The violation associated with the constraint. This is max(0, LHS - RHS) assuming
     *  a less-than type of constraint.
     */
    @Suppress("unused")
    fun violation(inputs: Map<String, Double>): Double {
        return maxOf(0.0, computeLHS(inputs) - ltRHSValue)
        //return -minOf(slack(inputs), 0.0)
    }

}