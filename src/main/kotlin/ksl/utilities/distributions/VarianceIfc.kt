package ksl.utilities.distributions

import kotlin.math.sqrt

/** Defines an interface for getting the variance of a
 *  distribution
 *
 * @author rossetti
 */
fun interface VarianceIfc {

    /** Returns the variance of the distribution if defined
     * @return the variance of the distribution
     */
    fun variance(): Double

    /**
     * Returns the standard deviation for the distribution
     * as the square root of the variance if it exists
     *
     * @return sqrt(variance())
     */
    fun standardDeviation(): Double = sqrt(variance())
}