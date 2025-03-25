package ksl.simopt.problem

import ksl.utilities.Interval


/**
 * enum to codify < and > in constraints for user convenience in problem definition.
 * (Internally all input and response constraints are implemented as <)
 * We could instead adopt one version (typically < in the literature)
 * and force the user to modify their coefficients.
 */
enum class InequalityType {
    LESS_THAN,
    GREATER_THAN
}

/**
 *  This class describes an optimization problem for use within simulation optimization algorithms.
 *  The general optimization problem is presented as minimizing the expected value of some function H(x), where
 *  x is some input parameters to the simulation and H(.) is the simulation model response for the objective
 *  function. The input parameters are assumed to be real-valued specified by a name between a lower and upper bound
 *  and a granularity. The granularity specifies the acceptable precision of the input. The problem can
 *  have a set of linear constraints. The linear constraints are a deterministic function of the inputs. In
 *  addition, a set of probabilistic constraints of the form E[G(x)] < c can be specified, where G(x) is some
 *  response from the simulation.
 *
 *  To use this class, the user first defines the objective function response name, the names of the input variables,
 *  and the names of the responses to appear in the problem. Then the reference to the class can be used
 *  to specify inputs and constraints.
 *
 *  @param objFnResponseName the name of the response within the simulation model. This name is used to extract the
 *  observed simulation values from the simulation
 *  @param inputNames the names of the inputs for the simulation model. These names are used to set values for
 *  the simulation when executing experiments. Any constraints specified on the input variables must use these names.
 *  @param responseNames the names of any responses that will appear in response constraints.
 */
class ProblemDefinition(
    val objFnResponseName: String,
    val inputNames: Set<String>,
    val responseNames: Set<String> = emptySet()
) {

    init {
        require(objFnResponseName.isNotBlank()) { "The objective function response name cannot be blank" }
        require(inputNames.isNotEmpty()) { "The set of input names cannot be empty" }
        for (name: String in inputNames) {
            require(name.isNotBlank()) { "An input name was blank" }
        }
        for (name: String in responseNames) {
            require(name.isNotBlank()) { "A response name was blank" }
        }
        require(!responseNames.contains(objFnResponseName)) { "The objective function response name cannot be within the set of response constraint names." }
    }

    private val myInputDefinitions = mutableMapOf<String, InputDefinition>()
    val inputs: List<InputDefinition>
        get() = myInputDefinitions.values.toList()
    private val myLinearConstraints = mutableListOf<LinearConstraint>()
    val linearConstraints: List<LinearConstraint>
        get() = myLinearConstraints.toList()
    private val myResponseConstraints = mutableListOf<ResponseConstraint>()
    val responseConstraints: List<ResponseConstraint>
        get() = myResponseConstraints.toList()

    private val myFunctionalConstraints = mutableListOf<FunctionalConstraint>()
    val functionalConstraints: List<FunctionalConstraint>
        get() = myFunctionalConstraints.toList()

    val inputLowerBounds: DoubleArray
        get() = myInputDefinitions.values.map { it.lowerBound }.toDoubleArray()

    val inputUpperBounds: DoubleArray
        get() = myInputDefinitions.values.map { it.upperBound }.toDoubleArray()

    val inputIntervals: List<Interval>
        get() = myInputDefinitions.values.map{it.interval}.toList()

    val inputMidPoints: DoubleArray
        get() = myInputDefinitions.values.map{it.interval.midPoint}.toDoubleArray()

    val inputRanges: DoubleArray
        get() = myInputDefinitions.values.map{it.interval.width}.toDoubleArray()

    val inputGranularities: DoubleArray
        get() = myInputDefinitions.values.map{it.granularity}.toDoubleArray()

    val inputSize: Int
        get() = myInputDefinitions.values.size

    var maxSamplesPerMember = 1E5.toInt()
        set(value) {
            require(value > 0) { "The maximum number of samples per member is $value, must be > 0" }
            field = value
        }

    fun input(name: String, lowerBound: Double, upperBound: Double, granularity: Double = 0.0): InputDefinition {
        require(name in inputNames) { "The name $name does not exist in the named inputs" }
        val inputData = InputDefinition(name, lowerBound, upperBound, granularity)
        myInputDefinitions[name] = inputData
        return inputData
    }

    fun input(name: String, interval: Interval, granularity: Double = 0.0): InputDefinition {
        return input(name, interval.lowerLimit, interval.upperLimit, granularity)
    }

    /**
     *  Creates an InputConstraint based on the supplied linear equation as specified by the map.
     *  The names in the map must be valid input names.  If an input name does not exist in the map,
     *  then the coefficient for that variable is assumed to be 0.0.
     *  @param equation the pair (name, value) represents the input name and the coefficient value in the linear
     *  constraint
     *  @param rhsValue the right-hand side of the constraint
     *  @param inequalityType the inequality type (less_than or greater_than)
     */
    fun inputConstraint(
        equation: Map<String, Double>,
        rhsValue: Double = 0.0,
        inequalityType: InequalityType = InequalityType.LESS_THAN
    ): LinearConstraint {
        for ((name, value) in equation) {
            require(name in inputNames) { "The name $name does not exist in the named inputs" }
        }
        val eqMap = mutableMapOf<String, Double>()
        for(name: String in inputNames) {
            eqMap[name] = equation[name]?:0.0
        }
        val ic = LinearConstraint(eqMap, rhsValue, inequalityType)
        myLinearConstraints.add(ic)
        return ic
    }

    fun responseConstraint(
        name: String,
        rhsValue: Double,
        inequalityType: InequalityType = InequalityType.LESS_THAN,
        violationPenalty: Double = 1000.0,
        violationExponent: Double = 2.0
    ): ResponseConstraint {
        require(name in responseNames) { "The name $name does not exist in the response names" }
        val rc = ResponseConstraint(name, rhsValue, inequalityType, violationPenalty, violationExponent)
        myResponseConstraints.add(rc)
        return rc
    }

    fun functionalConstraint(
        lhsFunc: ConstraintFunctionIfc,
        rhsValue: Double = 0.0,
        inequalityType: InequalityType = InequalityType.LESS_THAN
    ): FunctionalConstraint {
        val fc = FunctionalConstraint(inputNames, lhsFunc, rhsValue, inequalityType)
        myFunctionalConstraints.add(fc)
        return fc
    }

    /**
     *  Returns the coefficients of the constraints as a matrix. Assume we have the constraint
     *  A*x < b, then this function returns the A matrix.
     */
    fun linearConstraintMatrix(): Array<DoubleArray> {
        val array = mutableListOf<DoubleArray>()
        for (constraint in myLinearConstraints){
            array.add(constraint.coefficients)
        }
        return array.toTypedArray()
    }

    /**
     *  Returns the coefficients of the constraints as a matrix. Assume we have the constraint
     *  A*x < b, then this function returns the b vector.
     */
    fun linearConstraintsRHS() : DoubleArray {
        return myLinearConstraints.map { it.rhsValue }.toDoubleArray()
    }

    fun responseConstraintsRHS() : DoubleArray {
        return myResponseConstraints.map { it.rhsValue }.toDoubleArray()
    }

    fun responseConstraintsPenalties() : DoubleArray {
        return myResponseConstraints.map { it.violationPenalty }.toDoubleArray()
    }

    fun setResponseConstraintPenalties(penalty: Double)  {
        myResponseConstraints.forEach { it.violationPenalty = penalty }
    }

    /** The array x is mutated to hold values that have appropriate granularity based on the
     *  input definitions.
     *
     *  @param x the values of the inputs as an array. Assumes that the values are ordered in the
     *  same order as the names are defined for the problem
     *  @return the returned array is the same array as the input array but mutated. It is return for convenience.
     */
    fun roundToGranularity(x: DoubleArray) :DoubleArray {
        require(x.size == myInputDefinitions.size) { "The size of the input array is ${x.size}, but the number of inputs is ${myInputDefinitions.size}" }
        for((i, inputDefinition) in myInputDefinitions.values.withIndex()){
            x[i] = inputDefinition.roundToGranularity(x[i])
        }
        return x
    }

    /** The map values are mutated to hold values that have appropriate granularity based on the
     *  input definitions.
     *
     *  @param map the values of the inputs as map (name, value) pairs. The names in the map must be defined
     *  input names.
     *   @return the returned map is the same map as the input map but mutated. It is return for convenience.
     */
    fun roundToGranularity(map: MutableMap<String, Double>) : MutableMap<String, Double>{
        require(map.size == myInputDefinitions.size) { "The size of the input map is ${map.size}, but the number of inputs is ${myInputDefinitions.size}" }
        for((name, inputDefinition) in myInputDefinitions){
            require(name in map) {"The input name $name does not exist in the map"}
            map[name] = inputDefinition.roundToGranularity(map[name]!!)
        }
        return map
    }

    /**
     *  Translates the supplied array to named input pairs (name, value).
     *  Assumes that the order of the array is the same as the order of the defined names for the problem.
     */
    fun mapToInputNames(x: DoubleArray) : MutableMap<String, Double>{
        require(x.size == myInputDefinitions.size) { "The size of the input array is ${x.size}, but the number of inputs is ${myInputDefinitions.size}" }
        val map = mutableMapOf<String, Double>()
        for((i, inputDefinition) in myInputDefinitions.values.withIndex()){
            map[inputDefinition.name] = x[i]
        }
        return map
    }

    fun isInputFeasible(x: DoubleArray): Boolean {
        val rdx = roundToGranularity(x)
        val im = mapToInputNames(rdx)
        return isInputFeasible(im)
    }

    private fun isInputRangeFeasible(inputs: Map<String, Double>): Boolean {
        // check input limits first
        for((name, value) in  inputs){
            // the name must be in the input definitions by construction
            if (!myInputDefinitions[name]!!.contains(value)){
                return false
            }
        }
        return true
    }

    private fun isLinearConstraintFeasible(inputs: Map<String, Double>) : Boolean {
        for(ic in myLinearConstraints){
            if (!ic.isSatisfied(inputs)){
                return false
            }
        }
        return true
    }

    private fun isFunctionalConstraintFeasible(inputs: Map<String, Double>) : Boolean {
        for(ic in myFunctionalConstraints){
            if (!ic.isSatisfied(inputs)){
                return false
            }
        }
        return true
    }

    fun isInputFeasible(inputs: MutableMap<String, Double>): Boolean {
        require(inputs.size == myInputDefinitions.size) { "The size of the input map is ${inputs.size}, but the number of inputs is ${myInputDefinitions.size}" }
        val im = roundToGranularity(inputs)
        return isInputRangeFeasible(im) && isLinearConstraintFeasible(im) && isFunctionalConstraintFeasible(im)
    }

    fun linearConstraintsLHSValues(inputs: MutableMap<String, Double>) : DoubleArray{
        require(inputs.size == myInputDefinitions.size) { "The size of the input array is ${inputs.size}, but the number of inputs is ${myInputDefinitions.size}" }
        return DoubleArray(myLinearConstraints.size){ myLinearConstraints[it].computeLHS(inputs) }
    }


}