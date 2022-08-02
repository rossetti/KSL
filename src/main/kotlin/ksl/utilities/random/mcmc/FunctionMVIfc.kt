package ksl.utilities.random.mcmc

interface FunctionMVIfc {

    /**
     *
     * the expected size of the array
     */
    val dimension: Int

    /**
     * Returns the value of the function for the specified variable value.
     */
    fun fx(x: DoubleArray): Double
}