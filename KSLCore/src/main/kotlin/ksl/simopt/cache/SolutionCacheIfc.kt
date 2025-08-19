package ksl.simopt.cache

import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.Solutions

/**
 *  A solution cache should be designed to efficiently look up a solution
 *  based on a given set of input settings.
 */
interface SolutionCacheIfc : Map<ModelInputs, Solution> {

    /**
     *  If true, the cache will allow lookups of solutions
     *  based on the input settings. If false, the cache will
     *  not allow lookups of solutions based on the input settings.
     *  The default is true (allow cache lookups)
     */
    var allowCacheLookups: Boolean

    /**
     *  If true, the cache will allow puts of solutions
     *  based on the input settings. If false, the cache will
     *  not allow puts of solutions based on the input settings.
     *  The default is true (allow cache puts)
     */
    var allowCachePuts: Boolean

    /**
     *   If true input infeasible solutions are allowed to be
     *   saved in the cache. If false, input-infeasible solutions should not
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
    fun put(modelInputs: ModelInputs, solution: Solution): Solution?

    /**
     *  Looks up and removes the solution associated with the supplied input map.
     *  Null is returned if there is no associated solution. It is important
     *  that implementors handle the reduced size relative to the cache.
     */
    fun remove(modelInputs: ModelInputs): Solution?

    /**
     *  Places all input-solution pairs into the cache
     */
    fun putAll(from: Map<out ModelInputs, Solution>) {
        if (!allowCachePuts) return
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    /**
     *  Removes all items from the cache
     */
    fun clear()

    /**
     *  Retrieves the solutions associated with the requests
     */
    fun retrieveSolutions(requests: List<ModelInputs>): MutableMap<ModelInputs, Solution> {
        val mm = mutableMapOf<ModelInputs, Solution>()
        if (!allowCacheLookups) {
            return mm
        }
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
    operator fun set(modelInputs: ModelInputs, solution: Solution) {
        put(modelInputs, solution)
    }

    /**
     *  Retrieves the solution in the cache as a group of solutions
     *  @return the group of solutions
     */
    fun solutions() : Solutions
}

