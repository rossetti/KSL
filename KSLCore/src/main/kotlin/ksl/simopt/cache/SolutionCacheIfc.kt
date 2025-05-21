package ksl.simopt.cache

import ksl.simopt.evaluator.RequestData
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.Solutions

/**
 *  A solution cache should be designed to efficiently look up a solution
 *  based on a given set of input settings.
 */
interface SolutionCacheIfc : Map<RequestData, Solution> {

    /**
     *   If true input infeasible solutions are allowed to be
     *   saved in the cache. If false, input infeasible solutions should not
     *   be saved in the cache. Implementors are free to decide how to
     *   handle what to do with unsaved input infeasible solutions.
     *   The default is false (do not allow input infeasible solutions to be saved)
     */
    var allowInfeasibleSolutions: Boolean

    /**
     *  The maximum permitted size of the cache
     */
    val capacity: Int

    /**
     *  A rule to govern which solution should be evicted when the cache capacity is met.
     */
    var evictionRule: EvictionRuleIfc?

    /**
     *  Places the solution into the cache. It is important that implementors
     *  handle the insertion of infeasible inputs and ensure that the input
     *  map is associated with the solution
     */
    fun put(requestData: RequestData, solution: Solution): Solution?

    /**
     *  Looks up and removes the solution associated with the supplied input map.
     *  Null is returned if there is no associated solution. It is important
     *  that implementor handle the reduced size relative to the cache.
     */
    fun remove(requestData: RequestData): Solution?

    /**
     *  Places all input-solution pairs into the cache
     */
    fun putAll(from: Map<out RequestData, Solution>) {
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    /**
     *  Removes all items from the cache
     */
    fun clear() {
        for (key in keys) {
            remove(key)
        }
    }

    /**
     *  Retrieves the solutions associated with the requests
     */
    fun retrieveSolutions(requests: List<RequestData>): MutableMap<RequestData, Solution> {
        val mm = mutableMapOf<RequestData, Solution>()
        for (request in requests) {
            val solution = get(request)
            if (solution != null) {
                mm[request] = solution
            }
        }
        return mm
    }

    /**
     *  Allows use of bracket operator for setting values
     */
    operator fun set(requestData: RequestData, solution: Solution) {
        put(requestData, solution)
    }

    /**
     *  Retrieves the solution in the cache as a group of solutions
     *  @return the group of solutions
     */
    fun solutions() : Solutions
}

