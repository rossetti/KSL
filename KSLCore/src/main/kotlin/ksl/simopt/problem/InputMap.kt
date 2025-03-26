package ksl.simopt.problem

class InputMap(private val map: MutableMap<String, Double>) : Map<String, Double> by map {

    operator fun set(key: String, value: Double) {
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key] = value
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InputMap

        return map == other.map
    }

    override fun hashCode(): Int {
        return map.hashCode()
    }

    val names: List<String> = map.keys.toList()

    val points: DoubleArray
        get() = map.values.toDoubleArray()
}