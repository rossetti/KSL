package ksl.simopt.evaluator

class ResponseMap(
    private val map: MutableMap<String, MutableList<Double>>
) : Map<String, List<Double>> by map {

    operator fun set(key: String, list : MutableList<Double>) {
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key] = list
    }

    fun append(key: String, list: List<Double>){
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key]!!.addAll(list)
    }
}