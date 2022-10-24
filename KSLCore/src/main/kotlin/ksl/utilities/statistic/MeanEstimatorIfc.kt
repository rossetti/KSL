/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import ksl.utilities.statistic.EstimatorIfc
import kotlin.math.sqrt

/**
 * A minimal interface to define an estimator that will produce an estimate
 * of a population mean. We assume that the estimator has statistics
 * available that represent the count, average, and variance of a sample.
 * By default, the sample average is used as the estimate of the population
 * mean; however, implementors may override this behavior by overriding the
 * estimate() method.
 */
interface MeanEstimatorIfc : EstimatorIfc {
    override fun estimate(): Double {
        return average
    }

    /**
     * Gets the count of the number of the observations.
     *
     * @return A double representing the count
     */
    val count: Double

    /**
     * Gets the unweighted average of the observations.
     *
     * @return A double representing the average or Double.NaN if no
     * observations.
     */
    val average: Double

    /**
     * Gets the sample variance of the observations.
     *
     * @return A double representing the computed variance or Double.NaN if 1 or
     * less observations.
     */
    val variance: Double

    /**
     * Gets the sample standard deviation of the observations. Simply
     * the square root of getVariance()
     *
     * @return A double representing the computed standard deviation or Double.NaN
     * if 1 or less observations.
     */
    val standardDeviation: Double
        get() = sqrt(variance)
}