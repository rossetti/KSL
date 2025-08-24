package ksl.simopt.cache

import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.Solutions

/**
 *  A memory-based cache to hold solutions.  A simplified cache to avoid including
 *  more advanced caches in the dependency tree. This cache holds solutions
 *  in a map based on (RequestData, Solution) pairs.  The cache is capacity
 *  constrained to the specified capacity.  The user can supply an eviction rule that
 *  will identify a solution to evict when the capacity is reached. If no eviction
 *  rule is supplied, then by default the algorithm removes the first solution that
 *  is deterministically infeasible, infinite, or undefined in some manner. Then, it will
 *  identify the oldest solution with the largest penalized objective function for removal.
 *  @param capacity the maximum permitted size of the cache
 *  @param allowInfeasibleSolutions if true, the input infeasible solutions are allowed to be
 *  saved in the cache. If false, input-infeasible solutions are silently ignored.
 *  The default is false (do not allow input infeasible solutions to be saved)
 */
class MemorySolutionCache(
    override val capacity: Int = defaultCacheSize,
    override var allowInfeasibleSolutions: Boolean = false
) : SolutionCacheIfc {
    init {
        require(capacity >= 2) { "The cache's capacity must be >= 2" }
    }

    private val map: MutableMap<ModelInputs, Solution> = mutableMapOf()

    override var allowCacheLookups: Boolean = true

    override var allowCachePuts: Boolean = true

    override var evictionRule: EvictionRuleIfc? = null

    override val entries: Set<Map.Entry<ModelInputs, Solution>>
        get() = map.entries
    override val keys: Set<ModelInputs>
        get() = map.keys
    override val size: Int
        get() = map.size
    override val values: Collection<Solution>
        get() = map.values

    /**
     *  Removes all items from the cache
     */
    override fun clear() {
        map.clear()
    }

    override fun containsKey(key: ModelInputs): Boolean {
        if (!allowCacheLookups) return false
        return map.containsKey(key)
    }

    override fun containsValue(value: Solution): Boolean {
        if (!allowCacheLookups) return false
        return map.containsValue(value)
    }

    override fun get(key: ModelInputs): Solution? {
        if (!allowCacheLookups) return null
        return map[key]
    }

    override fun isEmpty(): Boolean {
        if (!allowCacheLookups) return true
        return map.isEmpty()
    }

    override fun remove(modelInputs: ModelInputs): Solution? {
        return map.remove(modelInputs)
    }

    override fun solutions(): Solutions {
        return Solutions(map.values.toList())
    }

    override fun put(modelInputs: ModelInputs, solution: Solution): Solution? {
        if (!allowCachePuts) return null
        require(modelInputs.inputs == solution.inputMap) { "The supplied input map is not associated with the supplied solution." }
        //TODO this may be too quiet
        if (!solution.isInputFeasible()) {
            return null
        }
        if (size == capacity) {
            val itemToEvict = evictionRule?.findEvictionCandidate(this) ?: findEvictionCandidate()
            remove(itemToEvict)
        }
        require(size < capacity) { "The eviction of members did not work. No capacity for item in the cache." }
        //TODO if a solution already exists in the map, should we do the merging here or should it stay as replacement only?
        return map.put(modelInputs, solution)
    }

    /**
     *  By default, the eviction candidate will be:
     *   - the first deterministically infeasible solution, or
     *   - the first solution with an infinite (or NaN) penalized objective function, or
     *   - the first solution with an infinite (or NaN) objective function or
     *   - the first solution (oldest) with the maximum penalized objective function.
     */
    fun findEvictionCandidate(): ModelInputs {
        if (size == 1) {
            return keys.toList().first()
        }
        for ((requestData, solution) in map) {
            // remove the first deterministically infeasible solution and return it
            if (!solution.isInputRangeFeasible() || !solution.isLinearConstraintFeasible() ||
                !solution.isFunctionalConstraintFeasible()
            ) {
                return requestData
            }
            // or remove the first infinite or bad constrained solution
            if (solution.penalizedObjFncValue.isNaN() || solution.penalizedObjFncValue.isInfinite()
                || solution.penalizedObjFncValue == Double.MAX_VALUE
            ) {
                return requestData
            }
            // or remove the first infinite or bad unconstrained solution
            if (solution.estimatedObjFncValue.isNaN() || solution.estimatedObjFncValue.isInfinite()
                || solution.estimatedObjFncValue == Double.MAX_VALUE
            ) {
                return requestData
            }
        }
        // If here, then solutions were deterministically feasible with non-problematic objective function values.
        // Find the oldest, largest solution to evict.
        var largestSolValue = Double.MAX_VALUE
        var candidate: ModelInputs? = null
        for ((inputMap, solution) in map) {
            val possibleMax = solution.penalizedObjFncValue
            if (possibleMax < largestSolValue) {
                candidate = inputMap
                largestSolValue = possibleMax
            }
        }
        // this should be safe because there must be more than 2 items, and they must be less than MAX_VALUE.
        return candidate!!
    }

    companion object {
        /**
         *  The default size for caches. By default, 1000.
         */
        var defaultCacheSize: Int = 1000
            set(value) {
                require(value >= 2) { "The minimum cache size is 2" }
                field = value
            }
    }

}