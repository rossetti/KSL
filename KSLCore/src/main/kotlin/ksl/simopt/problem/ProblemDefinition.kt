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
        require(responseNames.isNotEmpty()) { "There were no supplied response names because the set was empty" }
        require(name in responseNames) { "The name $name does not exist in the response names" }
        val rc = ResponseConstraint(name, rhsValue, inequalityType, violationPenalty, violationExponent)
        myResponseConstraints.add(rc)
        return rc
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

    fun responseConstraintPenalties(penalty: Double)  {
        myResponseConstraints.forEach { it.violationPenalty = penalty }
    }

    /** The array x is mutated to hold values that have appropriate granularity based on the
     *  input definitions.
     *
     *  @param x the values of the inputs as an array. Assumes that the values are ordered in the
     *  same order as the names are defined for the problem
     */
    fun roundToGranularity(x: DoubleArray) {
        require(x.size == myInputDefinitions.size) { "The size of the input array is ${x.size}, but the number of inputs is ${myInputDefinitions.size}" }
        for((i, inputDefinition) in myInputDefinitions.values.withIndex()){
            x[i] = inputDefinition.roundToGranularity(x[i])
        }
    }

    /** The map values are mutated to hold values that have appropriate granularity based on the
     *  input definitions.
     *
     *  @param map the values of the inputs as map (name, value) pairs. The names in the map must be defined
     *  input names.
     */
    fun roundToGranularity(map: MutableMap<String, Double>) {
        require(map.size == myInputDefinitions.size) { "The size of the input map is ${map.size}, but the number of inputs is ${myInputDefinitions.size}" }
        for((name, inputDefinition) in myInputDefinitions){
            require(name in map) {"The input name $name does not exist in the map"}
            map[name] = inputDefinition.roundToGranularity(map[name]!!)
        }
    }


}