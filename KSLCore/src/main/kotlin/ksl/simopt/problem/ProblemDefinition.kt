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

    private val myInputs = mutableMapOf<String, InputDefinition>()
    val inputs: List<InputDefinition>
        get() = myInputs.values.toList()
    private val myInputConstraints = mutableListOf<InputConstraint>()
    val inputConstraints: List<InputConstraint>
        get() = myInputConstraints.toList()
    private val myResponseConstraints = mutableListOf<ResponseConstraint>()
    val responseConstraints: List<ResponseConstraint>
        get() = myResponseConstraints.toList()

    var maxSamplesPerMember = 1E5.toInt()
        set(value) {
            require(value > 0) { "The maximum number of samples per member is $value, must be > 0" }
            field = value
        }

    fun input(name: String, lowerBound: Double, upperBound: Double, granularity: Double = 0.0): InputDefinition {
        require(name in inputNames) { "The name $name does not exist in the named inputs" }
        val inputData = InputDefinition(name, lowerBound, upperBound, granularity)
        myInputs[name] = inputData
        return inputData
    }

    fun input(name: String, interval: Interval, granularity: Double = 0.0): InputDefinition {
        return input(name, interval.lowerLimit, interval.upperLimit, granularity)
    }

    fun inputConstraint(
        equation: Map<String, Double>,
        rhsValue: Double = 0.0,
        inequalityType: InequalityType = InequalityType.LESS_THAN
    ): InputConstraint {
        for ((name, value) in equation) {
            require(name in inputNames) { "The name $name does not exist in the named inputs" }
        }
        val ic = InputConstraint(equation, rhsValue, inequalityType)
        myInputConstraints.add(ic)
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

}