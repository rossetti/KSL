/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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