package ksl.simopt.cache

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import java.util.function.BiConsumer

//TODO needs revision
fun interface EvictionRuleIfc {

    fun findEvictionCandidates(solutionCache: SolutionCacheIfc): List<InputMap>
}

interface SolutionCacheIfc : Map<InputMap, Solution> {

    var evictionRule: EvictionRuleIfc?

    fun put(key: InputMap, solution: Solution): Solution?

    fun remove(key: InputMap): Solution?

    fun put(request: EvaluationRequest, solution: Solution): Solution? {
        return put(request.inputMap, solution)
    }

    fun putAll(from: Map<out InputMap, Solution>) {
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    fun putAll(from: Map<out EvaluationRequest, Solution>) {
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

}

class MemorySolutionCache(
    val capacity: Int = Integer.MAX_VALUE
) : SolutionCacheIfc {

    private val map: MutableMap<InputMap, Solution> = mutableMapOf()

    override var evictionRule: EvictionRuleIfc? = null

    override val entries: Set<Map.Entry<InputMap, Solution>>
        get() = map.entries
    override val keys: Set<InputMap>
        get() = map.keys
    override val size: Int
        get() = map.size
    override val values: Collection<Solution>
        get() = map.values

    override fun containsKey(key: InputMap): Boolean {
        return map.containsKey(key)
    }

    override fun containsValue(value: Solution): Boolean {
        return map.containsValue(value)
    }

    override fun get(key: InputMap): Solution? {
        return map[key]
    }

    override fun isEmpty(): Boolean {
        return map.isEmpty()
    }

    override fun remove(key: InputMap): Solution? {
        return map.remove(key)
    }

    override fun put(key: InputMap, solution: Solution): Solution? {
        TODO("Not yet implemented")
    }

    private fun evictMembers() {
        val es = evictionRule?.findEvictionCandidates(this) ?: findEvictionCandidates()
        require(es.isNotEmpty()) { "The capacity was reached but there were no candidates found for eviction" }
    }

    private fun findEvictionCandidates(): List<InputMap> {
        val ms = mutableListOf<InputMap>()
        TODO("Not yet implemented")
        return ms
    }


}