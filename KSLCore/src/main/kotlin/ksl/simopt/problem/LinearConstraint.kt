package ksl.simopt.problem

import ksl.utilities.multiplyConstant


/**
 *  Represents a linear-constraint for a ProblemDefinition.
 *
 *  @param equation the variable name and the coefficient in the equation. The names of the input variables must
 *  not be blank.
 *  @param rhsValue the right-hand side value of the constraint. The default is 0.0.
 *  @param inequalityType the type of inequality, less-than or greater-than. The default is InequalityType.LESS_THAN
 */
data class LinearConstraint(
    val equation: Map<String, Double>,
    override var rhsValue: Double = 0.0,
    override val inequalityType: InequalityType = InequalityType.LESS_THAN
) : ConstraintIfc {

    init {
        for ((name, _) in equation) {
            require(name.isNotBlank()) { "An element name within the linear equation cannot be blank" }
        }
    }

    /**
     *  The input variable names that are in the equation ordered based on
     *  appearance in the equation.
     */
    val names: List<String>
        get() = equation.keys.toList()

    /**
     *  The coefficients associated with each input variable name within the equation.
     *  These are not adjusted for the direction of the inequality.
     */
    val coefficients: DoubleArray
        get() = equation.values.toDoubleArray()

    /**
     *  The coefficients associated with each input variable name within the equation.
     *  These are adjusted for the direction of the inequality to ensure an A*x < b orientation.
     */
    val adjustedCoefficients: DoubleArray
        get() = equation.values.toDoubleArray().multiplyConstant(inequalityFactor)

    /**
     *  @param name the name of the variable within the linear constraint
     *  @return the value of the coefficient for the variable or 0.0 if the variable does not
     *  appear in the equation
     */
    fun coefficient(name: String): Double {
        return equation[name] ?: 0.0
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation.  The returned value
     *  has been adjusted to ensure a less-than constraint orientation.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair.
     *  The names must be in the equation.
     *  @return the total value representing the left-hand side of the linear equation
     */
    override fun computeLHS(values: Map<String, Double>): Double {
        require(values.size == equation.size) { "The supplied map does not have the same number of names as the equation." }
        var sum = 0.0
        for ((name, value) in values) {
            require(equation.containsKey(name)) { "The supplied input name ($name) does not exist in the equation)" }
            sum = sum + value * equation[name]!!
        }
        //coefficients don't have inequality factor applied, need to apply to entire LHS
        return sum * inequalityFactor
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation. Assumes that the values
     *  are ordered in the same order as the input names. The returned value
     *  has been adjusted to ensure a less-than constraint orientation.
     *
     *  @param values the list containing the input variable value for each input variable name
     *  in the order in which the names are in the equation.
     *  @return the total value representing the left-hand side of the linear equation
     */
    fun computeLHS(values: DoubleArray): Double {
        require(values.size == equation.size) { "The supplied map does not have the same number of names as the equation." }
        var sum = 0.0
        val c = coefficients
        for ((i, v) in values.withIndex()) {
            sum = sum + c[i] * v
        }
        //coefficients don't have inequality factor applied, need to apply to entire LHS
        return sum * inequalityFactor
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied inputs for each input variable in the equation and checks if the constraint is satisfied.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair
     *  @return true if the constraint is satisfied, false otherwise.
     */
    override fun isSatisfied(values: Map<String, Double>): Boolean {
        return computeLHS(values) < ltRHSValue
    }

    /**
     *  Computes the value of the left-hand side of the constraint based on the
     *  supplied values for each input variable in the equation and checks if the constraint is satisfied.
     *
     *  @param values the map containing the input variable name and the current value of the input variable as a pair
     *  @return true if the constraint is satisfied, false otherwise.
     */
    fun isSatisfied(values: DoubleArray): Boolean {
        return computeLHS(values) < ltRHSValue
    }

    /**
     *  Returns the coefficients associated with the left-hand side of the constraint
     *  based on the supplied input names.  The coefficients are not adjusted for the
     *  direction of the inequality.
     *
     *  @param inputNames the list containing the input variable names. If the name is not
     *  in the equation, then the coefficient is considered 0.0
     *  @return an array of coefficients, one for each supplied name.
     */
    fun coefficients(inputNames: List<String>): DoubleArray {
        val coefficients = mutableListOf<Double>()
        for (name in inputNames) {
            coefficients.add(coefficient(name))
        }
        return coefficients.toDoubleArray()
    }


}