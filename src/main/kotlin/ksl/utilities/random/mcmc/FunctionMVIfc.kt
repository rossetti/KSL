package ksl.utilities.random.mcmc

interface FunctionMVIfc {

    /**
     * Returns the value of the function for the specified variable value.
     */
    fun fx(x: DoubleArray): Double
}