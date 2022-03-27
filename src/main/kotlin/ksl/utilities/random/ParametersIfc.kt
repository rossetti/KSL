package ksl.utilities.random

/**
 * Represents a general mechanism for setting and getting
 * the parameters of a function via an array of doubles
 *
 * @author rossetti
 */
interface ParametersIfc {
//TODO this should probably be an abstract property
    /**
     * Sets the parameters
     *
     * @param params an array of doubles representing the parameters
     */
    fun parameters(params: DoubleArray)

    /**
     * Gets the parameters
     *
     * @return Returns an array of the parameters
     */
    fun parameters(): DoubleArray
}