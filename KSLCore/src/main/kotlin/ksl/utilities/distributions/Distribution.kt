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

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.rootfinding.BisectionRootFinder

abstract class Distribution<T>(name: String? = null) : DistributionIfc<T>, IdentityIfc by Identity(name) {

    companion object {

        /**
         * Computes the inverse CDF by using the bisection method [ll,ul] must
         * contain the desired value. Initial search point is (ll+ul)/2.0
         *
         * [ll, ul] are defined on the domain of the CDF, i.e. the X values
         *
         * @param cdf a reference to the cdf
         * @param p must be in [0,1]
         * @param ll lower limit of search range, must be &lt; ul
         * @param ul upper limit of search range, must be &gt; ll
         * @return the inverse of the CDF evaluated at p
         */
        fun inverseContinuousCDFViaBisection(
            cdf: ContinuousDistributionIfc, p: Double,
            ll: Double, ul: Double
        ): Double {
            return inverseContinuousCDFViaBisection(cdf, p, ll, ul, (ll + ul) / 2.0)
        }

        /**
         * Computes the inverse CDF by using the bisection method [ll,ul] must
         * contain the desired value
         *
         * [ll, ul] are defined on the domain of the CDF, i.e. the x values
         *
         * @param cdf a reference to the cdf
         * @param p must be in [0,1]
         * @param ll lower limit of search range, must be &lt; ul
         * @param ul upper limit of search range, must be &gt; ll
         * @param initialX an initial starting point that must be in [ll,ul]
         * @return the inverse of the CDF evaluated at p
         */
        fun inverseContinuousCDFViaBisection(
            cdf: ContinuousDistributionIfc, p: Double,
            ll: Double, ul: Double, initialX: Double
        ): Double {
            if (ll >= ul) {
                val msg = "Supplied lower limit $ll must be less than upper limit $ul"
                throw IllegalArgumentException(msg)
            }
            if (p < ll || p > ul) {
                val msg = "Supplied probability was $p Probability must be [0,1)"
                throw IllegalArgumentException(msg)
            }
            val interval = Interval(ll, ul)
            val f = FunctionIfc { x -> cdf.cdf(x) - p }
            val rootFinder = BisectionRootFinder(f, interval, initialX)
            rootFinder.evaluate()
            return rootFinder.result
        }

        /** Searches starting at the value start until the CDF &gt; p
         * "start" must be the smallest possible value for the range of the CDF
         * as an integer.  This requirement is NOT checked
         *
         * Each value is incremented by 1. Thus, the range of possible
         * values for the CDF is assumed to be {start, start + 1, start + 2, etc.}
         *
         * @param df a reference to the discrete distribution
         * @param p the probability to evaluate, must be (0,1)
         * @param start the initial starting search position
         * @return the found inverse of the CDF found for p
         */
        fun inverseDiscreteCDFViaSearchUp(df: DiscreteDistributionIfc, p: Double, start: Int): Double {
            require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1)" }
            var i = start
            while (p > df.cdf(i.toDouble())) {
                i++
            }
            return i.toDouble()
        }
    }
}