package ksl.simopt.evaluator

class ResponseMap(
    private val map: MutableMap<String, MutableList<Double>>
) : Map<String, List<Double>> by map {

    /**
     *  Replaces the list for the specified key with the supplied list.
     *  The key must already exist in the response map.
     */
    operator fun set(key: String, list : MutableList<Double>) {
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key] = list
    }

    /**
     *  Appends the elements in the supplied list to the list associated
     *  with the supplied key within the response map. The supplied key
     *  must already exist in the response map.
     */
    fun append(key: String, list: List<Double>){
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key]!!.addAll(list)
    }

    /**
     *  Appends the data associated with the responses map into
     *  the response map. The supplied map of response data must include
     *  the names associated with this response map. The data from
     *  the arrays are appended to the data already within the response map.
     */
    fun appendAll(responses: Map<String, DoubleArray>){
        for((name, data) in map){
            require(responses.containsKey(name)) {"The response $name was not in the supplied map of responses"}
            append(name, responses[name]!!.asList())
        }
    }
}