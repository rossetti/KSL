package ksl.simopt.problem

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

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

/**
 *  Two InputMaps are considered equal if their (name, value) pairs are the same.
 *  This class prevents the keys from changing, but allows the changing of
 *  the data value associated with the keys resulting in a new instance. Thus,
 *  the underlying map cannot be changed.  This prevents an input map that
 *  is associated with a solution from being changed. InputMap instances
 *  are the keys for solution caches. Thus, we cannot change the key of
 *  the solution cache.
 *
 * @param map the map containing the (name, value) pairs associated with inputs
 * for the evaluation process.
 */
class InputMap(
    val problemDefinition: ProblemDefinition,
    private val map: MutableMap<String, Double>
) : Map<String, Double> by map, FeasibilityIfc {

    /**
     *  A copy of the input map as a mutable map
     */
    fun asMutableMap(): MutableMap<String, Double> {
        return HashMap(map)
    }

    /**
     *  Perturbs the current input values by the supplied step size.  Each input
     *  is randomly increased or decreased by the step size. If the perturbation
     *  will cause the value to be outsize of the defined input range, then the closest
     *  bound is used as the value. If the step size is less than the granularity
     *  for the granularity is used as the step size.  The return InputMap should be
     *  input feasible, but may not be feasible with respect to linear or functional
     *  constraints.
     *
     *  @param stepSize the amount of the perturbation. Must be greater than 0.0
     *  @param rnStream the stream to use in randomly choosing the direction of the step
     *  @return a new InputMap based on the current input map
     */
    fun perturbedBy(stepSize: Double, rnStream: RNStreamIfc = KSLRandom.defaultRNStream()): InputMap {
        require(stepSize > 0.0) { "The step size must be > 0.0" }
        val cm = HashMap(map)
        val idf = problemDefinition.inputDefinitions
        for ((name, value) in cm) {
            val iDefn = idf[name]!!
            val granularity = iDefn.granularity
            val step = rnStream.rSign() * maxOf(stepSize, granularity)
            val perturbedValue = value + step
            cm[name] = if (perturbedValue < iDefn.lowerBound) {
                iDefn.lowerBound
            } else if (perturbedValue > iDefn.upperBound){
                iDefn.upperBound
            } else {
                perturbedValue
            }
        }
        return InputMap(problemDefinition, cm)
    }

    /**
     *  Creates a new instance of an InputMap that is a copy of the current
     *  instance but with the value associated with the specified [inputName] changed to the provided [value]
     *  @param inputName the input name to change. Must be contained in the InputMap
     *  @param value the new value to assign
     *  @return the newly created instance
     */
    fun copy(inputName: String, value: Double): InputMap {
        require(map.containsKey(inputName)) { "The key ($inputName) is not in the map!" }
        val cm = HashMap(map)
        cm[inputName] = value
        return InputMap(problemDefinition, cm)
    }

    /**
     *  Randomly generates a new value for the named input variable and returns a new
     *  input map.  Randomization is uniformly distributed over the range of the
     *  input variable with no memory of its current value.
     *
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @param name the name of the input variable to randomize. Must be a valid name for
     *  the input map and thus for the problem. The default is a randomly selected name
     *  from the problem using the supplied random number stream.
     *  @return the newly created instance
     */
    fun randomizeInputVariable(
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String = problemDefinition.randomInputName(rnStream),
    ): InputMap {
        require(containsKey(name)) { "The input map does not contain the variable: $name" }
        return problemDefinition.randomizeInputValue(this, rnStream, name)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InputMap

        return map == other.map
    }

    override fun hashCode(): Int {
        return map.hashCode()
    }

    val names: List<String> = map.keys.toList()

    val inputValues: DoubleArray
        get() = map.values.toDoubleArray()

    /**
     *  The supplied input is considered input feasible if it is feasible with respect to
     *  the defined input parameter ranges, the linear constraints, and the functional constraints.
     *  @return true if the inputs are input feasible
     */
    override fun isInputFeasible(): Boolean{
        return problemDefinition.isInputFeasible(this)
    }

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within the ranges defined for the variables.
     *  False will be returned if at least one input variable is not within its defined range.
     *   @return true if the inputs are input feasible
     */
    override fun isInputRangeFeasible(): Boolean {
        return problemDefinition.isInputRangeFeasible(this)
    }

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within linear constraints.
     *  False will be returned if at least one linear constraint is infeasible.
     *   @return true if the inputs are feasible
     */
    override fun isLinearConstraintFeasible(): Boolean {
        return problemDefinition.isLinearConstraintFeasible(this)
    }

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within functional constraints.
     *  False will be returned if at least one functional constraint is infeasible.
     *   @return true if the inputs are feasible
     */
    override fun isFunctionalConstraintFeasible(): Boolean {
        return problemDefinition.isFunctionalConstraintFeasible(this)
    }

}