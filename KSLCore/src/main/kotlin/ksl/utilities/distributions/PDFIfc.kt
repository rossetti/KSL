package ksl.utilities.distributions

/** Represents the probability density function for
 *  1-d continuous distributions
 *
 * @author rossetti
 */
interface PDFIfc : DomainIfc {
    /** Returns the f(x) where f represents the probability
     * density function for the distribution.  Note this is not
     * a probability.
     *
     * @param x a double representing the value to be evaluated
     * @return f(x)
     */
    fun pdf(x: Double): Double
}