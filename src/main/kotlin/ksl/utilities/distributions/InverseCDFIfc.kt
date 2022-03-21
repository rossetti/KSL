package ksl.utilities.distributions

/**
 * Provides the inverse cumulative distribution function interface for the
 * distribution
 *
 * @author rossetti
 */
fun interface InverseCDFIfc {

    /**
     * Provides the inverse cumulative distribution function for the
     * distribution
     *
     * While closed form solutions for the inverse cdf may not exist, numerical
     * search methods can be used to solve F(X) = p.
     *
     * @param p The probability to be evaluated for the inverse, p must be [0,1]
     * or an IllegalArgumentException is thrown
     * @return The inverse cdf evaluated at the supplied probability
     */
    fun invCDF(p: Double): Double
}