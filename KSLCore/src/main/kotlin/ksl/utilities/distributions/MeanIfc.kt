package ksl.utilities.distributions

fun interface MeanIfc {
    /** Returns the mean or expected value of a distribution
     * @return double  the mean or expected value for the distribution
     */
    fun mean(): Double
}