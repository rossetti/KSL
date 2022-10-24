/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.utilities.statistic

import ksl.modeling.variable.LastValueIfc
import ksl.modeling.variable.ValueIfc
import ksl.utilities.Interval
import ksl.utilities.distributions.Normal

/**
 * The StatisticIfc interface presents a read-only view of a Statistic
 */
interface StatisticIfc : SummaryStatisticsIfc, GetCSVStatisticIfc, LastValueIfc, ValueIfc {
    /**
     * Gets the name of the Statistic
     *
     * @return The name as a String
     */
    val name: String

    /**
     * Gets the sum of the observations.
     *
     * @return A double representing the unweighted sum
     */
    val sum: Double

    /**
     * Gets the sum of squares of the deviations from the average This is the
     * numerator in the classic sample variance formula
     *
     * @return A double representing the sum of squares of the deviations from
     * the average
     */
    val deviationSumOfSquares: Double

    /**
     * Gets the last observed data point
     *
     * @return A double representing the last observations
     */
//    val lastValue: Double

    /**
     * Gets the kurtosis of the data
     *
     * @return A double representing the kurtosis
     */
    val kurtosis: Double

    /**
     * Gets the skewness of the data
     *
     * @return A double representing the skewness
     */
    val skewness: Double

    /**
     * Gets the standard error of the observations. Simply the generate standard
     * deviation divided by the square root of the number of observations
     *
     * @return A double representing the standard error or Double.NaN if &lt; 1
     * observation
     */
    val standardError: Double

    /**
     * Gets the confidence interval half-width. Simply the standard error
     * times the confidence coefficient
     *
     * @return A double representing the half-width or Double.NaN if &lt; 1
     * observation
     */
    val halfWidth: Double
        get() = halfWidth(confidenceLevel)

    /**
     * @return the width of the default confidence interval
     */
    val width: Double
        get() = width(confidenceLevel)

    /**
     * Gets the confidence interval half-width. Simply the standard error
     * times the confidence coefficient as determined by an appropriate sampling
     * distribution
     *
     * @param level the confidence level
     * @return A double representing the half-width or Double.NaN if &lt; 1
     * observation
     */
    fun halfWidth(level: Double): Double

    /**
     * @param  level the confidence level
     * @return the width of the confidence interval
     */
    fun width(level: Double): Double {
        return 2.0 * halfWidth(level)
    }

    /**
     * Gets the confidence level. The default is given by
     * Statistic.DEFAULT_CONFIDENCE_LEVEL = 0.95, which is a 95% confidence
     * level
     *
     * @return A double representing the confidence level
     */
    val confidenceLevel: Double

    /**
     * A confidence interval for the mean based on the confidence level
     *
     * @return the interval
     */
    val confidenceInterval: Interval
        get() = confidenceInterval(confidenceLevel)

    /**
     * A confidence interval for the mean based on the confidence level
     *
     * @param level the confidence level
     * @return the interval
     */
    fun confidenceInterval(level: Double): Interval {
        if (count < 1.0) {
            return Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        }
        val hw = halfWidth(level)
        val avg = average
        val ll = avg - hw
        val ul = avg + hw
        return Interval(ll, ul)
    }

    /**
     * Returns the relative error: getStandardError() / getAverage()
     *
     * @return the relative error
     */
    val relativeError: Double
        get() = if (average == 0.0) {
            Double.POSITIVE_INFINITY
        } else {
            standardError / average
        }

    /**
     * Returns the relative width of the default confidence interval: 2.0 *
     * getHalfWidth() / getAverage()
     *
     * @return the relative width
     */
    val relativeWidth: Double
        get() = relativeWidth(confidenceLevel)

    /**
     * Returns the relative width of the level of the confidence interval: 2.0 *
     * getHalfWidth(level) / getAverage()
     *
     * @param level the confidence level
     * @return the relative width for the level
     */
    fun relativeWidth(level: Double): Double {
        return if (average == 0.0) {
            Double.POSITIVE_INFINITY
        } else {
            2.0 * halfWidth(level) / average
        }
    }

    /**
     * Gets the lag-1 generate covariance of the unweighted observations. Note:
     * See Box, Jenkins, Reinsel, Time Series Analysis, 3rd edition,
     * Prentice-Hall, pg 31
     *
     * @return A double representing the covariance or Double.NaN if
     * &lt;=2 observations
     */
    val lag1Covariance: Double

    /**
     * Gets the lag-1 generate correlation of the unweighted observations. Note:
     * See Box, Jenkins, Reinsel, Time Series Analysis, 3rd edition,
     * Prentice-Hall, pg 31
     *
     * @return A double representing the correlation or Double.NaN if
     * &lt;=2 observations
     */
    val lag1Correlation: Double

    /**
     * Gets the Von Neumann Lag 1 test statistic for checking the hypothesis
     * that the data are uncorrelated Note: See Handbook of Simulation, Jerry
     * Banks editor, McGraw-Hill, pg 253.
     *
     * @return A double representing the Von Neumann test statistic
     */
    val vonNeumannLag1TestStatistic: Double

    /**
     * Returns the asymptotic p-value for the Von Nueumann Lag-1 Test Statistic:
     *
     * Normal.stdNormalComplementaryCDF(vonNeumannLag1TestStatistic);
     *
     * @return the p-value
     */
    val vonNeumannLag1TestStatisticPValue: Double
        get() = Normal.stdNormalComplementaryCDF(vonNeumannLag1TestStatistic)

    /**
     * When a data point having the value of (Double.NaN,
     * Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY) are presented it is
     * excluded from the summary statistics and the number of missing points is
     * noted. This method reports the number of missing points that occurred
     * during the collection
     *
     * @return the number missing
     */
    val numberMissing: Double

    /**
     * Computes the right most meaningful digit according to
     * (int)Math.floor(Math.log10(a*getStandardError())) See doi
     * 10.1287.opre.1080.0529 by Song and Schmeiser
     *
     * @param multiplier the std error multiplier
     * @return the meaningful digit
     */
    fun leadingDigitRule(multiplier: Double): Int

    fun statisticData(): StatisticData {
        return StatisticData(
            name,
            count,
            average,
            standardDeviation,
            standardError,
            halfWidth,
            confidenceLevel,
            min,
            max,
            sum,
            variance,
            deviationSumOfSquares,
            lastValue,
            kurtosis,
            skewness,
            lag1Covariance,
            lag1Correlation,
            vonNeumannLag1TestStatistic,
            numberMissing
        )
    }

    /**
     * Returns a String representation of the Statistic
     *
     * @return A String with basic summary statistics
     */
    override fun toString(): String

    /**
     * Fills up an array with the statistics defined by this interface
     * statistics[0] = getCount()
     * statistics[1] = getAverage()
     * statistics[2] = getStandardDeviation()
     * statistics[3] = getStandardError()
     * statistics[4] = getHalfWidth()
     * statistics[5] = getConfidenceLevel()
     * statistics[6] = getMin()
     * statistics[7] = getMax()
     * statistics[8] = getSum()
     * statistics[9] = getVariance()
     * statistics[10] = getDeviationSumOfSquares()
     * statistics[11] = getLastValue()
     * statistics[12] = getKurtosis()
     * statistics[13] = getSkewness()
     * statistics[14] = getLag1Covariance()
     * statistics[15] = getLag1Correlation()
     * statistics[16] = getVonNeumannLag1TestStatistic()
     * statistics[17] = getNumberMissing()
     *
     * @return an array of values
     */
    val asArray: DoubleArray
        get() {
            val statistics = DoubleArray(18)
            statistics[0] = count
            statistics[1] = average
            statistics[2] = standardDeviation
            statistics[3] = standardError
            statistics[4] = halfWidth
            statistics[5] = confidenceLevel
            statistics[6] = min
            statistics[7] = max
            statistics[8] = sum
            statistics[9] = variance
            statistics[10] = deviationSumOfSquares
            statistics[11] = lastValue
            statistics[12] = kurtosis
            statistics[13] = skewness
            statistics[14] = lag1Covariance
            statistics[15] = lag1Correlation
            statistics[16] = vonNeumannLag1TestStatistic
            statistics[17] = numberMissing
            return statistics
        }

    override val csvStatisticHeader: String
        get() {
            val sb = StringBuilder()
            sb.append("Statistic Name,")
            sb.append("Count,")
            sb.append("Average,")
            sb.append("Standard Deviation,")
            sb.append("Standard Error,")
            sb.append("Half-width,")
            sb.append("Confidence Level,")
            sb.append("Minimum,")
            sb.append("Maximum,")
            sb.append("Sum,")
            sb.append("Variance,")
            sb.append("Deviation Sum of Squares,")
            sb.append("Last value collected,")
            sb.append("Kurtosis,")
            sb.append("Skewness,")
            sb.append("Lag 1 Covariance,")
            sb.append("Lag 1 Correlation,")
            sb.append("Von Neumann Lag 1 Test Statistic,")
            sb.append("Number of missing observations")
            return sb.toString()
        }

    override val csvStatistic: String
        get() {
            val sb = StringBuilder()
            sb.append(name)
            sb.append(",")
            val stats = asArray
            for (i in stats.indices) {
                if (stats[i].isMissing()) {
                    println("$name has missing $i")
                    sb.append("")
                } else {
                    sb.append(stats[i])
                }
                if (i < stats.size - 1) {
                    sb.append(",")
                }
            }
            return sb.toString()
        }

    /**
     * Returns the values of all the statistics as a list of strings
     * The name is the first string
     *
     * @return the values of all the statistics as a list of strings
     */
    val asStrings: List<String>
        get() {
            val sb: MutableList<String> = ArrayList()
            sb.add(name)
            val stats = asArray
            for (i in stats.indices) {
                if (stats[i].isMissing()) {
                    sb.add("")
                } else {
                    sb.add(stats[i].toString())
                }
            }
            return sb
        }

    /**
     * Gets the CSV header values as a list of strings
     *
     * @return the CSV header values as a list of strings
     */
    val csvHeader: List<String>
        get() {
            val sb: MutableList<String> = ArrayList()
            sb.add("Statistic Name")
            sb.add("Count")
            sb.add("Average")
            sb.add("Standard Deviation")
            sb.add("Standard Error")
            sb.add("Half-width")
            sb.add("Confidence Level")
            sb.add("Minimum")
            sb.add("Maximum")
            sb.add("Sum")
            sb.add("Variance")
            sb.add("Deviation Sum of Squares")
            sb.add("Last value collected")
            sb.add("Kurtosis")
            sb.add("Skewness")
            sb.add("Lag 1 Covariance")
            sb.add("Lag 1 Correlation")
            sb.add("Von Neumann Lag 1 Test Statistic")
            sb.add("Number of missing observations")
            return sb
        }

    /**
     * Fills the map with the values of the statistics. Key is statistic label
     * and value is the value of the statistic. The keys are:
     * "Count"
     * "Average"
     * "Standard Deviation"
     * "Standard Error"
     * "Half-width"
     * "Confidence Level"
     * "Minimum"
     * "Maximum"
     * "Sum"
     * "Variance"
     * "Deviation Sum of Squares"
     * "Last value collected"
     * "Kurtosis"
     * "Skewness"
     * "Lag 1 Covariance"
     * "Lag 1 Correlation"
     * "Von Neumann Lag 1 Test Statistic"
     * "Number of missing observations"
     */
    val statisticsAsMap: Map<String, Double>
        get() {
            val stats: MutableMap<String, Double> = LinkedHashMap()
            stats["Count"] = count
            stats["Average"] = average
            stats["Standard Deviation"] = standardDeviation
            stats["Standard Error"] = standardError
            stats["Half-width"] = halfWidth
            stats["Confidence Level"] = confidenceLevel
            stats["Minimum"] = min
            stats["Maximum"] = max
            stats["Sum"] = sum
            stats["Variance"] = variance
            stats["Deviation Sum of Squares"] = deviationSumOfSquares
            stats["Last value collected"] = lastValue
            stats["Kurtosis"] = kurtosis
            stats["Skewness"] = skewness
            stats["Lag 1 Covariance"] = lag1Covariance
            stats["Lag 1 Correlation"] = lag1Correlation
            stats["Von Neumann Lag 1 Test Statistic"] = vonNeumannLag1TestStatistic
            stats["Number of missing observations"] = numberMissing
            return stats
        }
}