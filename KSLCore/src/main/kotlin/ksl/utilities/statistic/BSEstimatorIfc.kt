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