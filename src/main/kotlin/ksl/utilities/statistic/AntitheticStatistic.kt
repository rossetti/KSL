package ksl.utilities.statistic

private var StatCounter: Int = 0

/**
 * In progress...
 */
class AntitheticStatistic(theName: String = "AntitheticStatistic_${++StatCounter}") : AbstractStatistic(theName) {

    private var myStatistic: Statistic = Statistic(theName)
    private var myOddValue = 0.0
    override val count: Double
        get() = myStatistic.count
    override val sum: Double
        get() = myStatistic.sum
    override val average: Double
        get() = myStatistic.average
    override val deviationSumOfSquares: Double
        get() = myStatistic.deviationSumOfSquares
    override val variance: Double
        get() = myStatistic.variance
    override val min: Double
        get() = myStatistic.min
    override val max: Double
        get() = myStatistic.max
    override val kurtosis: Double
        get() = myStatistic.kurtosis
    override val skewness: Double
        get() = myStatistic.skewness
    override val standardError: Double
        get() = myStatistic.standardError

    override fun halfWidth(level: Double): Double {
        return myStatistic.halfWidth(level)
    }

    override val lag1Covariance: Double
        get() = myStatistic.lag1Covariance
    override val lag1Correlation: Double
        get() = myStatistic.lag1Correlation
    override val vonNeumannLag1TestStatistic: Double
        get() = myStatistic.vonNeumannLag1TestStatistic

    override fun leadingDigitRule(multiplier: Double): Int {
        return myStatistic.leadingDigitRule(multiplier)
    }

    override fun collect(obs: Double) {
        if (count % 2 == 0.0) { // even
            val avg = (obs + myOddValue) / 2.0
            collect(avg)
        } else {
            myOddValue = obs // save the odd value
        }
    }

    override fun toString(): String {
        return myStatistic.toString()
    }

}