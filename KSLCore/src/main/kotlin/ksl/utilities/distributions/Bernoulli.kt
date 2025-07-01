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
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.floor

/**
 * Provides an implementation of the Bernoulli
 * distribution with success probability (p)
 * P(X=1) = p
 * P(X=0) = 1-p
 * @param successProb the probability of success, must be between (0,1)
 * @param name an optional name
 */
class Bernoulli(successProb: Double= 0.5, name: String? = null) :
    Distribution(name), DiscreteDistributionIfc, RVParametersTypeIfc by RVType.Bernoulli {

    init {
        require(!(successProb <= 0.0 || successProb >= 1.0)) { "Probability must be (0,1)" }
    }

    constructor(params: DoubleArray, name: String?) : this(params[0], name)

    // private data members
    var probOfSuccess : Double = successProb
        set(prob) {
            require(!(prob <= 0.0 || prob >= 1.0)) { "Probability must be (0,1)" }
            field = prob
        }

    override fun instance(): Bernoulli {
        return Bernoulli(probOfSuccess)
    }

    override fun cdf(x: Double): Double {
        val xx: Int = floor(x).toInt()
        return if (xx < 0) {
            0.0
        } else if (xx == 0) {
            (1.0 - probOfSuccess)
        } else  //if (x >= 1)
        {
            1.0
        }
    }

    override fun pmf(x: Double): Double {
        return if (KSLMath.equal(x, 0.0)) {
            (1.0 - probOfSuccess)
        } else if (KSLMath.equal(x, 1.0)) {
            probOfSuccess
        } else {
            0.0
        }
    }

    override fun pmf(i: Int): Double {
        return if (i == 0) {
            (1.0 - probOfSuccess)
        } else if (i == 1) {
            probOfSuccess
        } else {
            0.0
        }
    }

    override fun mean(): Double {
        return probOfSuccess
    }

    override fun variance(): Double {
        return probOfSuccess * (1.0 - probOfSuccess)
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        return if (p <= probOfSuccess) {
            1.0
        } else {
            0.0
        }
    }

    override fun randomVariable(streamNumber: Int, streamProvider: RNStreamProviderIfc): BernoulliRV {
        return BernoulliRV(probOfSuccess, streamNumber, streamProvider)
    }

    override fun parameters(params: DoubleArray) {
        probOfSuccess = params[0]
    }

    override fun parameters(): DoubleArray {
        return doubleArrayOf(probOfSuccess)
    }

    override fun toString(): String {
        return "Bernoulli(probOfSuccess=$probOfSuccess)"
    }

}