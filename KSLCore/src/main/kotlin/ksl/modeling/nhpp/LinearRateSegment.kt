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
package ksl.modeling.nhpp

import ksl.utilities.math.KSLMath
import kotlin.math.sqrt

/**
 * @author rossetti
 */
class LinearRateSegment(
    cumRateLL: Double, timeLL: Double, rateLL: Double, timeUL: Double,
    rateUL: Double
) : RateSegmentIfc {
    /**
     * the slope for the interval
     */
    var slope = 0.0
        protected set

    /** the rate at the lower limit of the interval
     *
     */
    override var rateAtLowerTimeLimit = 0.0
        protected set

    /**
     * the rate at the upper limit of the interval
     */
    override var rateAtUpperTimeLimit = 0.0
        protected set

    /**
     * the width of the interval on the cumulative rate scale (crWidth = crUL - crLL)
     */
    protected var crWidth = 0.0

    /**
     * the lower limit of the interval on cumulative rate scale
     */
    protected var crLL = 0.0

    /** the upper limit of the interval on the cumulative rate scale
     *
     */
    protected var crUL = 0.0

    /**
     * the width of the interval on the time scale (tWidth = tUL - tLL)
     */
    protected var tWidth = 0.0

    /**
     * the lower limit of the interval on the time scale
     */
    protected var tLL = 0.0

    /**
     * the upper limit of the interval on the time scale
     */
    protected var tUL = 0.0

    init {
        tLL = timeLL
        rateAtLowerTimeLimit = rateLL
        tUL = timeUL
        rateAtUpperTimeLimit = rateUL
        tWidth = tUL - tLL
        slope = (rateAtUpperTimeLimit - rateAtLowerTimeLimit) / tWidth
        crLL = cumRateLL
        crUL = crLL + 0.5 * (rateAtUpperTimeLimit + rateAtLowerTimeLimit) * (tUL - tLL)
        crWidth = crUL - crLL
    }

    override fun newInstance(): LinearRateSegment {
        return LinearRateSegment(crLL, tLL, rateAtLowerTimeLimit, tUL, rateAtUpperTimeLimit)
    }

    override fun contains(time: Double): Boolean {
        return tLL <= time && time <= tUL
    }

    override fun getRate(time: Double): Double {
        return if (KSLMath.equal(slope, 0.0)) {
            rateAtLowerTimeLimit
        } else {
            rateAtLowerTimeLimit + slope * (time - tLL)
        }
    }

    override val timeRangeLowerLimit: Double
        get() = tLL
    override val upperTimeLimit: Double
        get() = tUL
    override val timeWidth: Double
        get() = tWidth
    override val cumulativeRateRangeLowerLimit: Double
        get() = crLL
    override val cumulativeRateUpperLimit: Double
        get() = crUL
    override val cumulativeRateIntervalWidth: Double
        get() = crWidth

    override fun getCumulativeRate(time: Double): Double {
        return if (KSLMath.equal(slope, 0.0)) {
            crLL + rateAtLowerTimeLimit * (time - tLL)
        } else {
            crLL + rateAtLowerTimeLimit * (time - tLL) + 0.5 * slope * (time - tLL) * (time - tLL)
        }
    }

    override fun getInverseCumulativeRate(cumRate: Double): Double {
        return if (KSLMath.equal(slope, 0.0)) {
            if (KSLMath.equal(rateAtLowerTimeLimit, 0.0)) {
                Double.NaN
            } else {
                tLL + (cumRate - crLL) / rateAtLowerTimeLimit
            }
        } else {
            val n = 2.0 * (cumRate - crLL)
            val d =
                rateAtLowerTimeLimit + sqrt(rateAtLowerTimeLimit * rateAtLowerTimeLimit + 2.0 * slope * (cumRate - crLL))
            tLL + n / d
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(" [")
        sb.append(rateAtLowerTimeLimit)
        sb.append(",")
        sb.append(rateAtUpperTimeLimit)
        sb.append("] slope = ")
        sb.append(slope)
        sb.append(" [")
        sb.append(tLL)
        sb.append(",")
        sb.append(tUL)
        sb.append("] width = ")
        sb.append(tWidth)
        sb.append(" [")
        sb.append(crLL)
        sb.append(",")
        sb.append(crUL)
        sb.append("] cr width = ")
        sb.append(crWidth)
        sb.append("\n")
        return sb.toString()
    }
}