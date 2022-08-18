package ksl.utilities.statistic

data class StatisticData (
    val name: String,
    val count: Double,
    val average: Double,
    val standardDeviation: Double,
    val standardError: Double,
    val halfWidth: Double,
    val confidenceLevel: Double,
    val min: Double,
    val max: Double,
    val sum: Double,
    val variance: Double,
    val deviationSumOfSquares: Double,
    val lastValue: Double,
    val kurtosis: Double,
    val skewness: Double,
    val lag1Covariance: Double,
    val lag1Correlation: Double,
    val vonNeumannLag1TestStatistic: Double,
    val numberMissing: Double
) : Comparable<StatisticData> {

    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     * The natural ordering is based on the average
     *
     * @param other The statistic to compare this statistic to
     * @return Returns a negative integer, zero, or a positive integer if this
     * object is less than, equal to, or greater than the specified object based on the average
     */
    override operator fun compareTo(other: StatisticData): Int {
        return average.compareTo(other.average)
    }
}