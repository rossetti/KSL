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
     *  Computes the sum of the probabilities over the provided range.
     *  If the range is closed a..b then the end point b is included in the
     *  sum. If the range is open a..&ltb then the point b is not included
     *  in the sum.
     */
    fun pmf(range: IntRange) : Double {
        var sum = 0.0
        for (i in range){
            sum = sum + pmf(i)
        }
        return sum
    }

//    /**
//     * Returns the probability of being in the open [interval],
//     * Be careful, this is Pr{lower limit &lt;= X &lt; upper limit}
//     * which excludes the upper limit.
//     * @return the probability
//     */
//    fun openRightCDF(interval: Interval): Double {
//        return openRightCDF(interval.lowerLimit, interval.upperLimit)
//    }
//
//    /** Returns the Pr{x1 &lt;= X &lt; x2} for the distribution.
//     * Be careful, this is Pr{x1 &lt;= X &lt; x2}
//     * which excludes the upper limit.
//     *
//     * @param x1 a double representing the lower limit
//     * @param x2 a double representing the upper limit
//     * @return the probability
//     * @throws IllegalArgumentException if x1 &gt;= x2
//     */
//    fun openRightCDF(x1: Double, x2: Double): Double {
//        require(x1 < x2) { "x1 = $x1 >= x2 = $x2 in openRightCDF(x1,x2)" }
//        return strictlyLessCDF(x2) - cdf(x1)
//    }
}