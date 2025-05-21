package ksl.simopt.problem

interface FeasibilityIfc {
    /**
     *  The supplied input is considered input feasible if it is feasible with respect to
     *  the defined input parameter ranges, the linear constraints, and the functional constraints.
     *  @return true if the inputs are input feasible
     */
    fun isInputFeasible(): Boolean

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within the ranges defined for the variables.
     *  False will be returned if at least one input variable is not within its defined range.
     *   @return true if the inputs are input feasible
     */
    fun isInputRangeFeasible(): Boolean

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within linear constraints.
     *  False will be returned if at least one linear constraint is infeasible.
     *   @return true if the inputs are feasible
     */
    fun isLinearConstraintFeasible(): Boolean

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within functional constraints.
     *  False will be returned if at least one functional constraint is infeasible.
     *   @return true if the inputs are feasible
     */
    fun isFunctionalConstraintFeasible(): Boolean
}