package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.LaplaceRV
import ksl.utilities.random.rvariable.LogisticRV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.*

/**
 * Laplace(location, scale) distribution
 * @param location must be a real number
 * @param scale must be greater than 0
 * @param name an optional name/label
 */
class Laplace(
    var location: Double = 0.0,
    scale: Double = 1.0,
    name: String? = null
) : Distribution<Laplace>(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(scale > 0.0) { "The scale must be > 0.0" }
    }

    var scale = scale
        set(value) {
            require(value > 0.0) { "The scale must be > 0.0" }
            field = value
        }

    override fun domain(): Interval {
        return Interval()
    }

    override fun mean(): Double {
        return location
    }

    override fun variance(): Double {
        return 2.0*scale*scale
    }

    override fun instance(): Laplace {
        return Laplace(location, scale)
    }

    override fun cdf(x: Double): Double {
        val z = (x - location) / scale
        return if (x <= location){
            0.5*exp(z)
        } else {
            (1.0 - 0.5*exp(-z))
        }
    }

    override fun pdf(x: Double): Double {
        val z = abs(x - location) / scale
        val num = exp(-z)
        val denom = 2.0*scale
        return num / denom
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1)" }
        if (p <= 0.0) {
            return Double.NEGATIVE_INFINITY
        }
        if (p >= 1.0) {
            return Double.POSITIVE_INFINITY
        }
        val u = p - 0.5
        return location - scale * sign(u) * ln(1.0 - 2.0 * abs(u))
    }

    /**
     *  @param params must have two elements, where params[0] = location and
     *  params[1] = scale
     */
    override fun parameters(params: DoubleArray) {
        require(params.size == 2) { "There must be two parameters" }
        location = params[0]
        scale = params[1]
    }

    /**
     *  params[0] = location
     *  params[1] = scale
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(location, scale)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return LaplaceRV(location, scale, stream)
    }

    override fun toString(): String {
        return "Laplace(location=$location, scale=$scale)"
    }
}