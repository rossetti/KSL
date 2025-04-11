package ksl.simopt.problem

/**
 *  Two InputMaps are considered equal if their (name, value) pairs are the same.
 *  This class prevents the keys from changing, but allows the changing of
 *  the data value associated with the keys.
 *
 * @param map the map containing the (name, value) pairs associated with inputs
 * for the evaluation process.
 */
class InputMap(
    val problemDefinition: ProblemDefinition,
    private val map: MutableMap<String, Double>
) : Map<String, Double> by map {

    operator fun set(key: String, value: Double) {
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key] = value
    }

    /**
     *  Randomly generates a new value for the named input variable and returns the updated
     *  input map.  Randomization is uniformly distributed over the range of the
     *  input variable with no memory of its current value.
     *
     *  @param name the name of the input variable to randomize. Must be a valid name for
     *  the input map and thus for the problem.
     *  @param roundToGranularity true indicates that the point should be rounded to
     *  the appropriate granularity. The default is true.
     *  @return the replaced value from the map
     */
    fun randomizeInputVariable(name:String, roundToGranularity: Boolean = true) : Double {
        require(containsKey(name)) { "The input map does not contain the variable: $name" }
        val current = map[name]!!
        problemDefinition.randomizeInputValue(name, this, roundToGranularity)
        return current
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