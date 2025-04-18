package ksl.simopt.problem

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

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

//    operator fun set(key: String, value: Double) {
//        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
//        map[key] = value
//    }

    /**
     *  Creates a new instance of an InputMap that is a copy of the current
     *  instance but with value associated with the specified [inputName] changed to the provided [value]
     *  @param inputName the input name to change. Must be contained in the InputMap
     *  @param value the new value to assign
     *  @return the newly created instance
     */
    fun copy(inputName: String, value: Double) : InputMap {
        require(map.containsKey(inputName)) {"The key ($inputName) is not in the map!"}
        val cm = HashMap(map)
        cm[inputName] = value
        return InputMap(problemDefinition, cm)
    }

    /**
     *  Randomly generates a new value for the named input variable and returns a new
     *  input map.  Randomization is uniformly distributed over the range of the
     *  input variable with no memory of its current value.
     *
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @param name the name of the input variable to randomize. Must be a valid name for
     *  the input map and thus for the problem. The default is a randomly selected name
     *  from the problem using the supplied random number stream.
     *  @return the newly created instance
     */
    fun randomizeInputVariable(
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name:String = problemDefinition.randomInputName(rnStream),
    ) : InputMap {
        require(containsKey(name)) { "The input map does not contain the variable: $name" }
        return problemDefinition.randomizeInputValue(this, rnStream, name)
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