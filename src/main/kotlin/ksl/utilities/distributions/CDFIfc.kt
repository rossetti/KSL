package ksl.utilities.distributions

/** Provides an interface for functions related to
 *  a cumulative distribution function CDF
 *
 * @author rossetti
 */
fun interface CDFIfc {
    /** Returns the F(x) = Pr{X &lt;= x} where F represents the
     * cumulative distribution function
     *
     * @param x a double representing the upper limit
     * @return a double representing the probability
     */
    fun cdf(x: Double): Double

    /** Returns the Pr{x1&lt;=X&lt;=x2} for the distribution
     *
     * @param x1 a double representing the lower limit
     * @param x2 a double representing the upper limit
     * @return cdf(x2)-cdf(x1)
     * @throws IllegalArgumentException if x1 &gt; x2
     */
    fun cdf(x1: Double, x2: Double): Double {
        if (x1 > x2) {
            val msg = "x1 = $x1 > x2 = $x2 in cdf(x1,x2)"
            throw IllegalArgumentException(msg)
        }
        return cdf(x2) - cdf(x1)
    }

    /** Computes the complementary cumulative probability
     * distribution function for given value of x
     * @param x The value to be evaluated
     * @return The probability, 1-P{X&lt;=x}
     */
    fun complementaryCDF(x: Double): Double {
        return 1.0 - cdf(x)
    }
}