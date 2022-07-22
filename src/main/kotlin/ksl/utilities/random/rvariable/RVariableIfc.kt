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
package ksl.utilities.random.rvariable

import ksl.utilities.PreviousValueIfc
import ksl.utilities.observers.DoubleChangedIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.statistic.Statistic
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.*

/**
 * An interface for defining random variables. The methods sample() and getValue() gets a new
 * value of the random variable sampled accordingly.  The method getPreviousValue() returns
 * the value from the last call to sample() or getValue(). The value returned by getPreviousValue() stays
 * the same until the next call to sample() or getValue().  The methods sample() or getValue() always get
 * the next random value.  If sample() or getValue() is never called then getPreviousValue() returns Double.NaN.
 * Use sample() or getValue() to get a new random value and use getPreviousValue() to get the last sampled value.
 *
 *
 * The preferred approach to creating random variables is to sub-class RVariable.
 */
interface RVariableIfc : RandomIfc, NewAntitheticInstanceIfc, PreviousValueIfc, DoubleChangedIfc {

    /**
     * The set of pre-defined distribution types
     */
    enum class RVType {
        Bernoulli, Beta, ChiSquared, Binomial, Constant, DUniform, Exponential, Gamma,
        GeneralizedBeta, Geometric, JohnsonB, Laplace, LogLogistic, Lognormal, NegativeBinomial,
        Normal, PearsonType5, PearsonType6, Poisson, ShiftedGeometric, Triangular,
        Uniform, Weibull, DEmpirical, Empirical
    }

    /**
     * The randomly generated value. Each value
     * will be different
     */
    override val value: Double
        get() = sample()

    /**
     * The randomly generated value. Each value
     * will be different
     * @return the randomly generated value, same as using property value
     */
    override fun value(): Double = value

    /**
     * @param n the number of values to sum, must be 1 or more
     * @return the sum of n values of getValue()
     */
    fun sum(n: Int): Double {
        require(n >= 1) { "There must be at least 1 in the sum" }
        var sum = 0.0
        for (i in 1..n) {
            sum = sum + value
        }
        return sum
    }

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter values
     */
    fun instance(stream: RNStreamIfc): RVariableIfc

    /**
     * @return a new instance with same parameter values, with a different stream
     */
    fun instance(): RVariableIfc {
        return instance(KSLRandom.nextRNStream())
    }

    override fun antitheticInstance(): RVariableIfc {
        return instance(rnStream.antitheticInstance())
    }

    operator fun plus(other: RVariableIfc): RVariableIfc {
        return RVFunction(this, other, Double::plus)
    }

    operator fun times(other: RVariableIfc): RVariableIfc {
        return RVFunction(this, other, Double::times)
    }

    operator fun div(other: RVariableIfc): RVariableIfc {
        return RVFunction(this, other, Double::div)
    }

    operator fun minus(other: RVariableIfc): RVariableIfc {
        return RVFunction(this, other, Double::minus)
    }

    operator fun plus(x: Double): RVariableIfc {
        return RVFunction(this, ConstantRV(x), Double::plus)
    }

    operator fun times(x: Double): RVariableIfc {
        return RVFunction(this, ConstantRV(x), Double::times)
    }

    operator fun div(x: Double): RVariableIfc {
        return RVFunction(this, ConstantRV(x), Double::div)
    }

    operator fun minus(x: Double): RVariableIfc {
        return RVFunction(this, ConstantRV(x), Double::minus)
    }

    fun pow(x: Double): RVariableIfc {
        return RVFunction(this, ConstantRV(x), Double::pow)
    }

}

operator fun Double.plus(other: RVariableIfc): RVariableIfc {
    return RVFunction(ConstantRV(this), other, Double::plus)
}

operator fun Double.minus(other: RVariableIfc): RVariableIfc {
    return RVFunction(ConstantRV(this), other, Double::minus)
}

operator fun Double.div(other: RVariableIfc): RVariableIfc {
    return RVFunction(ConstantRV(this), other, Double::div)
}

operator fun Double.times(other: RVariableIfc): RVariableIfc {
    return RVFunction(ConstantRV(this), other, Double::times)
}

fun abs(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::abs)
}

fun acos(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::acos)
}

fun acosh(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::acosh)
}

fun asin(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::asin)
}

fun asinh(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::asinh)
}

fun atan(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::atan)
}

fun atanh(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::atanh)
}

fun ceil(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::ceil)
}

fun cos(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::cos)
}

fun cosh(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::cosh)
}

fun exp(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::exp)
}

fun floor(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::floor)
}

fun ln(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::ln)
}

fun log10(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::log10)
}

fun log2(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::log2)
}

fun Double.pow(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::pow)
}

fun round(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::round)
}

fun sin(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::sin)
}

fun sinh(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::sinh)
}

fun sqrt(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::sqrt)
}

fun tan(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::tan)
}

fun tanh(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::tanh)
}

fun truncate(rv: RVariableIfc): RVariableIfc {
    return RVUFunction(rv, ::truncate)
}





