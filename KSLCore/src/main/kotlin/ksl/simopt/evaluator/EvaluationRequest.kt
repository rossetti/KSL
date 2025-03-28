package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap

class EvaluationRequest(
    numReplications: Int,
    val inputMap: InputMap
) {
    init {
        require(numReplications >= 1) {"The number of replications must be >= 1"}
    }

    var replications: Int = numReplications
        set(value) {
            require(value >= 1) {"The number of replications must be >= 1"}
            field = value
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EvaluationRequest

        return inputMap == other.inputMap
    }

    override fun hashCode(): Int {
        return inputMap.hashCode()
    }

    val inputValues: DoubleArray
        get() = inputMap.inputValues


}