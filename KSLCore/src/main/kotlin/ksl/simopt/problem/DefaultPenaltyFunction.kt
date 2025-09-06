package ksl.simopt.problem

import kotlin.math.pow

class DefaultPenaltyFunction(

) : PenaltyFunctionIfc {

    var initialLinearConstraintPenalty: Double = defaultInitialPenality
        set(value) {
            require(value > 0.0) { "The default initial penalty must be > 0.0" }
            field = value
        }
    var initialFunctionalConstraintPenalty: Double = defaultInitialPenality
        set(value) {
            require(value > 0.0) { "The default initial penalty must be > 0.0" }
            field = value
        }
    var initialResponseConstraintPenalty: Double = defaultInitialPenality
        set(value) {
            require(value > 0.0) { "The default initial penalty must be > 0.0" }
            field = value
        }

    var exponent: Double = defaultExponent
        set(value) {
            require(value > 0.0) { "The exponent must be > 0.0" }
            field = value
        }

    override fun penalty(
        inputMap: InputMap,
        responseAverages: Map<String, Double>,
        iterationCounter: Int
    ): Double {
        val linearViolations = inputMap.problemDefinition.totalLinearConstrainViolations(inputMap)
        val functionalViolations = inputMap.problemDefinition.totalFunctionalConstraintViolations(inputMap)
        val responseViolations = inputMap.problemDefinition.totalResponseConstraintViolations(responseAverages)
        val iterationFactor = iterationCounter.toDouble().pow(exponent)
        val totalPenalty = (linearViolations * initialLinearConstraintPenalty +
                functionalViolations * initialFunctionalConstraintPenalty +
                responseViolations * initialResponseConstraintPenalty) * iterationFactor
        if (totalPenalty.isNaN()) return Double.MAX_VALUE
        if (totalPenalty.isInfinite()) return Double.MAX_VALUE
        return totalPenalty
    }

    companion object {

        var defaultInitialPenality: Double = 1000.0
            set(value) {
                require(value > 0.0) { "The default initial penalty must be > 0.0" }
                field = value
            }

        var defaultExponent: Double = 2.0
            set(value) {
                require(value > 0.0) { "The default exponent must be > 0.0" }
                field = value
            }

        var defaultPenaltyFunction: PenaltyFunctionIfc = DefaultPenaltyFunction()
    }
}