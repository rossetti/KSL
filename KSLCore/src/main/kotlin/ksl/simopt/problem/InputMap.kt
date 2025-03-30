package ksl.simopt.problem

/**
 *  Two InputMaps are considered equal if their (name, value) pairs are the same.
 *  This class prevents the keys from changing, but allows the changing of
 *  the data value associated with the keys.
 *
 * @param map the map containing the (name, value) pairs associated with inputs
 * for the evaluation process.
 */
open class InputMap(
    val problemDefinition: ProblemDefinition,
    private val map: MutableMap<String, Double>
) : Map<String, Double> by map {

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

    val inputValues: DoubleArray
        get() = map.values.toDoubleArray()
}