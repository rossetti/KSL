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

import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GeometricRV
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.*


/** The geometric distribution is the probability distribution of
 * the number Y = X − 1 of failures before the first success,
 * supported on the set { 0, 1, 2, 3, ... }, where X is the number of
 * Bernoulli trials needed to get one success.
 *
 * @param successProb the probability of success
 * @param name an optional label/name
 */
class Geometric(successProb: Double = 0.5, name: String? = null) : Distribution<Geometric>(name),
    DiscreteDistributionIfc, LossFunctionDistributionIfc, GetRVariableIfc {

    init {
        require(!(successProb < 0.0 || successProb > 1.0)) { "Probability must be [0,1]" }
    }

    /**
     * Constructs a Geometric using the supplied parameters array
     * parameters[0] is probability of success
     * @param parameters the parameter array
     */
    constructor(parameters: DoubleArray) : this(parameters[0], null)

    /**
     * The probability of success on a trial
     */
    var pSuccess = successProb
        set(probability) {
            require(!(probability < 0.0 || probability > 1.0)) { "Probability must be [0,1]" }
            field = probability
        }

    /**
     * The probability of failure on a trial
     */
    val pFailure = 1 - pSuccess


    override fun instance(): Geometric {
        return Geometric(pSuccess)
    }

    override fun mean(): Double {
        return pFailure / pSuccess
    }

    override fun variance(): Double {
        return pFailure / (pSuccess * pSuccess)
    }

    /**  Sets the parameters using the supplied array
     * parameters[0] is probability of success
     * @param params the parameter array
     */
    override fun parameters(params: DoubleArray) {
        pSuccess = params[0]
    }

    /** Gets the parameters as an array
     * parameters[0] is probability of success
     *
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(pSuccess)
    }

    /** computes the pmf of the distribution
     * f(x) = p(1-p)^(x) for x&gt;=0, 0 otherwise
     *
     * @param i the value to evaluate
     * @return the probability
     */
    override fun pmf(i: Int): Double {
        return if (i < 0) {
            0.0
        } else pSuccess * pFailure.pow(i.toDouble())
    }

    /** computes the cdf of the distribution
     * F(X&lt;=x)
     *
     * @param x, must be &gt;= lower limit
     * @return the cumulative probability
     */
    override fun cdf(x: Double): Double {
        if (x < 0.0) {
            return 0.0
        }
        val xx = floor(x) + 1.0
        return 1 - pFailure.pow(xx)
    }

    /** Gets the inverse cdf for the distribution
     *
     * @param p Must be in range [0,1)
     */
    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be (0,1)" }
        require(!KSLMath.equal(p, 1.0, KSLMath.machinePrecision))
        { "Supplied probability was within machine precision of 1.0 Probability must be (0,1)" }
        require(!KSLMath.equal(p, 0.0, KSLMath.machinePrecision))
        { "Supplied probability was within machine precision of 0.0 Probability must be (0,1)" }
        return ceil(ln(1.0 - p) / ln(1.0 - pSuccess) - 1.0)
    }

    override fun firstOrderLossFunction(x: Double): Double {
        val mu = mean()
        return if (x < 0.0) {
            floor(abs(x)) + mu
        } else if (x > 0.0) {
            val p = pSuccess
            val q = pFailure
            val b = (1.0 - p) / p
            val g1 = b * q.pow(x)
            g1
        } else  // x== 0.0
        {
            mu
        }
    }

    override fun secondOrderLossFunction(x: Double): Double {
        val mu = mean()
        val sbm = 0.5 * (variance() + mu * mu - mu) // 1/2 the 2nd binomial moment
        return if (x < 0.0) {
            var s = 0.0
            var y = 0
            while (y > x) {
                s = s + firstOrderLossFunction(y.toDouble())
                y--
            }
            s + sbm
        } else if (x > 0.0) {
            val p = pSuccess
            val q = pFailure
            val b = (1.0 - p) / p
            val g2 = b * b * q.pow(x)
            g2
        } else  // x == 0.0
        {
            sbm
        }
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return GeometricRV(pSuccess, stream)
    }

    override fun toString(): String {
        return "Geometric(pSuccess=$pSuccess)"
    }

    companion object {
        fun canMatchMoments(vararg moments: Double): Boolean {
            require(moments.isNotEmpty()) { "Must provide a mean." }
            val mean = moments[0]
            return mean > 0
        }

        fun parametersFromMoments(vararg moments: Double): DoubleArray {
            require(canMatchMoments(*moments)) { "Mean must be positive. You provided " + moments[0] + "." }
            val mean = moments[0]
            val p = 1 / (mean + 1)
            return doubleArrayOf(p)
        }

        fun createFromMoments(vararg moments: Double): Geometric {
            val prob = parametersFromMoments(*moments)
            return Geometric(prob)
        }
    }

}