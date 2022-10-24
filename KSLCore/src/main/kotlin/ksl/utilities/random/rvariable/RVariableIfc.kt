/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.random.rvariable

import ksl.utilities.PreviousValueIfc
import ksl.utilities.observers.DoubleEmitterIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
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
 * The preferred approach to creating random variables is to subclass RVariable.
 */
interface RVariableIfc : RandomIfc, NewAntitheticInstanceIfc, PreviousValueIfc, DoubleEmitterIfc {

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





