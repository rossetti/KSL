/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

data class StatisticData (
    val name: String,
    val count: Double,
    val average: Double,
    val standardDeviation: Double,
    val standardError: Double,
    val halfWidth: Double,
    val confidenceLevel: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    val min: Double,
    val max: Double,
    val sum: Double,
    val variance: Double,
    val deviationSumOfSquares: Double,
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