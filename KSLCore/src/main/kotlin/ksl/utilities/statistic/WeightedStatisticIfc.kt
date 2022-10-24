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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.utilities.statistic

import ksl.utilities.IdentityIfc


/**
 * If the observation or the weight is
 * * infinite or NaN, then the observation should not be recorded and the number of missing observations
 * * is incremented. If the observed weight is negative or 0.0, then the observation should not be recorded and
 * * the number of missing observations is incremented.
 *
 * @author rossetti
 */
interface WeightedStatisticIfc : IdentityIfc, GetCSVStatisticIfc {
    /**
     * Gets the count of the number of the observations.
     *
     * @return A double representing the count
     */
    val count: Double

    /**
     * Gets the maximum of the observations.
     *
     * @return A double representing the maximum
     */
    val max: Double

    /**
     * Gets the minimum of the observations.
     *
     * @return A double representing the minimum
     */
    val min: Double

    /**
     * Gets the sum of the observed weights.
     *
     * @return A double representing the sum of the weights
     */
    val sumOfWeights: Double

    /**
     * Gets the weighted average of the collected observations.
     *
     * @return A double representing the weighted average or Double.NaN if no
     * observations.
     */
    val weightedAverage: Double

    /**
     * Gets the weighted sum of observations observed.
     *
     * @return A double representing the weighted sum
     */
    val weightedSum: Double

    /**
     * Gets the weighted sum of squares for observations observed.
     *
     * @return A double representing the weighted sum of squares
     */
    val weightedSumOfSquares: Double

    /**
     * Clears all the statistical accumulators
     */
    fun reset()

    /**
     * Gets the last observed data point
     *
     * @return A double representing the last observed observation
     */
    val lastValue: Double

    /**
     * Gets the last observed weight
     *
     * @return A double representing the last observed weight
     */
    val lastWeight: Double

    /**
     * When a data point having the value of (Double.NaN,
     * Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY) are presented it is
     * excluded from the summary statistics and the number of missing points is
     * noted. If the weight is infinite or less than or equal to zero, then the data point should not be recorded.
     * This method reports the number of missing points that occurred
     * during the collection.
     *
     * @return the number missing
     */
    val numberMissing: Double

    /**
     *
     * @return the unweighted sum of all of the observed data
     */
    val unWeightedSum: Double

    /**
     *
     * @return the unweighted average of all the observed data
     */
    val unWeightedAverage: Double
        get() = if (count == 0.0) {
            Double.NaN
        } else unWeightedSum / count
}