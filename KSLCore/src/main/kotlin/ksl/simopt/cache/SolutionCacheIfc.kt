package ksl.simopt.cache

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import java.util.function.BiConsumer

//TODO needs revision

interface SolutionCacheIfc : Map<InputMap, Solution> {

    fun put(key: InputMap, value: Solution): Solution?

    fun remove(key: InputMap): Solution?

    fun put(request: EvaluationRequest, value: Solution) : Solution? {
        return put(request.inputMap, value)
    }

    fun putAll(from: Map<out InputMap, Solution>){
        for((input, solution) in from) {
            put(input, solution)
        }
    }

    fun putAll(from: Map<out EvaluationRequest, Solution>){
        for((input, solution) in from) {
            put(input, solution)
        }
    }

    fun clear(){
        for (key in keys){
            remove(key)
        }
    }

    fun retrieveSolutions(requests: List<EvaluationRequest>): MutableMap<InputMap, Solution> {
        val mm = mutableMapOf<InputMap, Solution>()
        for(request in requests){
            val solution = get(request.inputMap)
            if (solution != null) {
                mm[request.inputMap] = solution
            }
        }
        return mm
    }

}

class MemorySolutionCache(
    val capacity: Int = Integer.MAX_VALUE
) : SolutionCacheIfc {

    private val map: MutableMap<InputMap, Solution> = mutableMapOf()

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

    override fun put(key: InputMap, value: Solution): Solution? {
        TODO("Not yet implemented")
    }

    override fun remove(key: InputMap): Solution? {
        TODO("Not yet implemented")
    }

}