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