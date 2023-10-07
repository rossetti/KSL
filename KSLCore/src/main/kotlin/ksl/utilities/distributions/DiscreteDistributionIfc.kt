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

package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.ParametersIfc
import ksl.utilities.random.rvariable.GetRVariableIfc

interface DiscreteDistributionIfc : CDFIfc, PMFIfc, InverseCDFIfc, GetRVariableIfc, ParametersIfc {

    /**
     *  Computes Pr{x &lt X } for the distribution.
     */
    fun strictlyLessCDF(x: Double) : Double {
        return cdf(x) - pmf(x)
    }

    /**
     * Returns the probability of being in the open [interval],
     * Be careful, this is Pr{lower limit&lt; X &lt;=upper limit}
     * which excludes the lower limit.
     * @return the probability
     */
    fun openLeftIntervalProbability(interval: Interval): Double {
        return openLeftIntervalProbability(interval.lowerLimit, interval.upperLimit)
    }

    /** Returns the Pr{x1 &lt X &lt;= x2} for the distribution.
     * Be careful, this is Pr{x1 &lt X &lt;= x2}
     * which excludes the lower limit.
     *
     * @param x1 a double representing the lower limit
     * @param x2 a double representing the upper limit
     * @return the probability
     * @throws IllegalArgumentException if x1 &gt; x2
     */
    fun openLeftIntervalProbability(x1: Double, x2: Double): Double {
        require(x1 <= x2) { "x1 = $x1 > x2 = $x2 in cdf(x1,x2)" }
        return cdf(x2) - cdf(x1)
    }
}