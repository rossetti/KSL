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

import ksl.simopt.evaluator.EstimatedResponseIfc

/**
 * A minimal interface to define an estimator that will produce an estimate
 * of a population mean. We assume that the estimator has statistics
 * available that represent the count, average, and variance of a sample.
 * By default, the sample average is used as the estimate of the population
 * mean; however, implementors may override this behavior by overriding the
 * estimate() method.
 */
interface MeanEstimateIfc : EstimateIfc, EstimatedResponseIfc {
    override fun estimate(): Double {
        return average
    }
}