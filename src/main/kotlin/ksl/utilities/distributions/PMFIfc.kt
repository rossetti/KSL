package ksl.utilities.distributions

/**
 * Represents the probability mass function for 1-d discrete distributions
 *
 * @author rossetti
 */
fun interface PMFIfc {

    /**
     * Returns the f(x) where f represents the probability mass function for the
     * distribution.
     *
     * @param x a double representing the value to be evaluated
     * @return f(x) the P(X=x)
     */
    fun pmf(x: Double): Double
}