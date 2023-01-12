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

import ksl.utilities.max
import ksl.utilities.min

interface BSEstimatorIfc {
    fun getEstimate(data: DoubleArray): Double

    /**
     * A predefined EstimatorIfc that estimates the mean of the data
     */
    class Average : BSEstimatorIfc {
        private val s: Statistic = Statistic()
        override fun getEstimate(data: DoubleArray): Double {
            s.reset()
            s.collect(data)
            return s.average
        }
    }

    /**
     * A predefined EstimatorIfc that estimates the variance of the data
     */
    class Variance : BSEstimatorIfc {
        private val s: Statistic = Statistic()
        override fun getEstimate(data: DoubleArray): Double {
            s.reset()
            s.collect(data)
            return s.variance
        }
    }

    /**
     * A predefined EstimatorIfc that estimates the median of the data
     */
    class Median : BSEstimatorIfc {
        override fun getEstimate(data: DoubleArray): Double {
            return Statistic.median(data)
        }
    }

    /**
     * A predefined EstimatorIfc that estimates the minimum of the data
     */
    class Minimum : BSEstimatorIfc {
        override fun getEstimate(data: DoubleArray): Double {
            return data.min()
        }
    }

    /**
     * A predefined EstimatorIfc that estimates the maximum of the data
     */
    class Maximum : BSEstimatorIfc {
        override fun getEstimate(data: DoubleArray): Double {
            return data.max()
        }
    }
}