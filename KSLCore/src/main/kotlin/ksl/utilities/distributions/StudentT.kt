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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.StudentTRV
import kotlin.math.*

/** The Student T distribution
 *
 * See http://www.mth.kcl.ac.uk/~shaww/web_page/papers/Tdistribution06.pdf
 * See http://en.wikipedia.org/wiki/Student's_t-distribution
 *
 * This implementation limits the degrees of freedom to be greater
 * than or equal to 1.0
 *
 * @author rossetti
 * @param theDegreesOfFreedom  degrees of freedom
 * @param name an optional name/label
 */
class StudentT(theDegreesOfFreedom: Double = 1.0, name: String? = null) : Distribution<StudentT>(name),
    ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(theDegreesOfFreedom >= 1) { "The degrees of freedom must be >= 1.0" }
    }

    var degreesOfFreedom = theDegreesOfFreedom
        set(value) {
            require(value >= 1) { "The degrees of freedom must be >= 1.0" }
            field = value
        }

    /** Constructs a StudentT distribution with
     * parameters[0] = degrees of freedom
     * @param parameters An array with the degrees of freedom
     */
    constructor(parameters: DoubleArray) : this(parameters[0], null)

    override fun instance(): StudentT {
        return StudentT(degreesOfFreedom)
    }

    override fun domain(): Interval {
        return Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    }

    /** Used in the binary search to set the search interval for the inverse
     * CDF. The default addFactor is 6.0
     *
     * The interval will be:
     * start = Normal.stdNormalInvCDF(p)
     * ll = start - getIntervalFactor()*getStandardDeviation();
     * ul = start + getIntervalFactor()*getStandardDeviation();
     */
    var intervalFactor: Double = 6.0
        set(factor) {
            require(factor >= 1.0) { "The interval factor must >= 1" }
            field = factor
        }

    override fun parameters(params: DoubleArray) {
        degreesOfFreedom = params[0]
    }

    override fun parameters(): DoubleArray {
        return doubleArrayOf(degreesOfFreedom)
    }

    override fun mean(): Double {
        return 0.0
    }

    override fun variance(): Double {
        return if (degreesOfFreedom > 2.0) {
            degreesOfFreedom / (degreesOfFreedom - 2.0)
        } else {
            Double.NaN
        }
    }

    override fun pdf(x: Double): Double {
        if (degreesOfFreedom == 1.0) {
            val d = PI * (1.0 + x * x)
            return 1.0 / d
        }
        if (degreesOfFreedom == 2.0) {
            return (2.0 + x * x).pow(-1.5)
        }
        val b1 = 1.0 / sqrt(degreesOfFreedom * Math.PI)
        val p = (degreesOfFreedom + 1.0) / 2.0
        val lnn1 = Gamma.gammaFunction(p)
        val lnd1 = Gamma.gammaFunction(degreesOfFreedom / 2.0)
        val tmp = lnn1 - lnd1
        val b2 = exp(tmp)
        val b3 = 1.0 / (1.0 + x * x / degreesOfFreedom).pow(p)
        return b1 * b2 * b3
    }

    override fun cdf(x: Double): Double {
        if (degreesOfFreedom == 1.0) {
            return 0.5 + 1.0 / PI * atan(x)
        }
        if (degreesOfFreedom == 2.0) {
            var d = x / sqrt(2.0 + x * x)
            d = 1.0 + d
            return d / 2.0
        }
        val y = degreesOfFreedom / (x * x + degreesOfFreedom)
        val a = degreesOfFreedom / 2.0
        val b = 1.0 / 2.0
        val rBeta = Beta.regularizedIncompleteBetaFunction(y, a, b)
        return 0.5 * (1.0 + sign(x) * (1.0 - rBeta))
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be (0,1)" }
        if (p <= 0.0) {
            return Double.NEGATIVE_INFINITY
        }
        if (p >= 1.0) {
            return Double.POSITIVE_INFINITY
        }
        if (degreesOfFreedom == 1.0) {
            return tan(PI * (p - 0.5))
        }
        if (degreesOfFreedom == 2.0) {
            val n = 2.0 * p - 1.0
            val d = sqrt(2.0 * p * (1.0 - p))
            return n / d
        }

        //use normal distribution to initialize bisection search
        val start = Normal.stdNormalInvCDF(p)
        val ll = start - intervalFactor * standardDeviation()
        val ul = start + intervalFactor * standardDeviation()
        return inverseContinuousCDFViaBisection(this, p, ll, ul, start)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return StudentTRV(degreesOfFreedom, stream)
    }

    companion object {
        /** A default instance for easily computing Student-T values
         *
         */
        val defaultT = StudentT()

        /** A convenience method that uses defaultT to
         * return the value of the CDF at the supplied x
         * This method has the side effect of changing
         * the degrees of freedom defaultT
         *
         * @param dof the degrees of freedom
         * @param x the value to evaluate
         * @return the CDF value
         */
        fun cdf(dof: Double, x: Double): Double {
            defaultT.degreesOfFreedom = dof
            return defaultT.cdf(x)
        }

        /** A convenience method that uses defaultT to
         * return the value of the inverse CDF at the supplied p
         * This method has the side effect of changing
         * the degrees of freedom for defaultT
         *
         * @param dof the degrees of freedom
         * @param p the value to evaluate
         * @return the inverse
         */
        fun invCDF(dof: Double, p: Double): Double {
            defaultT.degreesOfFreedom = dof
            return defaultT.invCDF(p)
        }
    }
}