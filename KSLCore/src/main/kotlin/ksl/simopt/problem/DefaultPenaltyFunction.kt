package ksl.simopt.problem

import ksl.simopt.evaluator.Solution
import kotlin.math.pow

/**
 *  The default penalty function for the optimization problem defined by the problem definition.
 *  The default penalty function is a linear combination of the
 *  linear, functional, and response constraint penalties.
 */
class DefaultPenaltyFunction() : PenaltyFunctionIfc {

    /**
     *  The initial penalty for the linear constraints.
     *  The default value is 1000.0.
     *  @see defaultInitialPenality
     */
    var initialLinearConstraintPenalty: Double = defaultInitialPenality
        set(value) {
            require(value > 0.0) { "The default initial penalty must be > 0.0" }
            field = value
        }

    /**
     *  The initial penalty for the functional constraints.
     *  The default value is 1000.0.
     *  @see defaultInitialPenality
     */
    var initialFunctionalConstraintPenalty: Double = defaultInitialPenality
        set(value) {
            require(value > 0.0) { "The default initial penalty must be > 0.0" }
            field = value
        }

    /**
     *  The initial penalty for the response constraints.
     *  The default value is 1000.0.
     *  @see defaultInitialPenality
     */
    var initialResponseConstraintPenalty: Double = defaultInitialPenality
        set(value) {
            require(value > 0.0) { "The default initial penalty must be > 0.0" }
            field = value
        }

    /**
     *  The exponent for the penalty function. The default value is 2.0.
     *  @see defaultExponent
     */
    var exponent: Double = defaultExponent
        set(value) {
            require(value > 0.0) { "The exponent must be > 0.0" }
            field = value
        }

    override fun penalty(
        solution: Solution,
        iterationCounter: Int
    ): Double {
        val inputMap = solution.inputMap
        val responseAverages = solution.responseAverages
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

        /**
         * The default initial penalty for the linear, functional, and response constraints.
         * The default value is 1000.0.
         */
        var defaultInitialPenality: Double = 1000.0
            set(value) {
                require(value > 0.0) { "The default initial penalty must be > 0.0" }
                field = value
            }

        /**
         * The default exponent for the penalty function. The default value is 2.0.
         */
        var defaultExponent: Double = 2.0
            set(value) {
                require(value > 0.0) { "The default exponent must be > 0.0" }
                field = value
            }

        var defaultPenaltyFunction: PenaltyFunctionIfc = DefaultPenaltyFunction()
    }
}