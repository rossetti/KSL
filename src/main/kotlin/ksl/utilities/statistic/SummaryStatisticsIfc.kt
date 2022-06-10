package ksl.utilities.statistic

interface SummaryStatisticsIfc : MeanEstimatorIfc {
    /**
     * Gets the minimum of the observations.
     *
     * @return A double representing the minimum
     */
    val min: Double

    /**
     * Gets the maximum of the observations.
     *
     * @return A double representing the maximum
     */
    val max: Double
}