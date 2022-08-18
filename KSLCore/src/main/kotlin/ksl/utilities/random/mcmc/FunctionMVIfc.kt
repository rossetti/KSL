package ksl.utilities.random.mcmc

interface FunctionMVIfc {

    /**
     *
     * the expected size of the array
     */
    val dimension: Int

    /**
     * Returns the value of the function for the specified variable value.
     * The implementor of fx should check if the array size is the
     * same as the dimension of the function
     */
    fun fx(x: DoubleArray): Double
}