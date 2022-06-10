package ksl.utilities.statistic

/** A functional interface that produces some estimate of some
 * quantity of interest.
 */
interface EstimatorIfc {
    /**
     * @return the estimated value
     */
    fun estimate(): Double
}