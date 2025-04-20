package ksl.simopt.cache

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.Solutions
import ksl.simopt.problem.InputMap

interface SolutionCacheIfc : Map<InputMap, Solution> {

    var allowInfeasibleSolutions: Boolean

    var evictionRule: EvictionRuleIfc?

    fun put(inputMap: InputMap, solution: Solution): Solution?

    fun remove(inputMap: InputMap): Solution?

    fun put(request: EvaluationRequest, solution: Solution): Solution? {
        return put(request.inputMap, solution)
    }

    fun putAll(from: Map<out InputMap, Solution>) {
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    fun putAllRequests(from: Map<out EvaluationRequest, Solution>) {
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    fun clear() {
        for (key in keys) {
            remove(key)
        }
    }

    fun retrieveSolutions(requests: List<EvaluationRequest>): MutableMap<InputMap, Solution> {
        val mm = mutableMapOf<InputMap, Solution>()
        for (request in requests) {
            val solution = get(request.inputMap)
            if (solution != null) {
                mm[request.inputMap] = solution
            }
        }
        return mm
    }

    operator fun set(inputMap: InputMap, solution: Solution) {
        put(inputMap, solution)
    }

    /**
     *  Retrieves the solution in the cache as a group of solutions
     *  @return the group of solutions
     */
    fun solutions() : Solutions

}

