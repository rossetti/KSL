package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.*
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/**
 * Logistic(location, scale) distribution
 * @param location must be a real number
 * @param scale must be greater than 0
 * @param name an optional name/label
 */
class Logistic(
    var location: Double = 0.0,
    scale: Double = 1.0,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc,
    RVParametersTypeIfc by RVType.Logistic, MomentsIfc {

    init {
        require(scale > 0.0) { "The scale must be > 0.0" }
    }

    var scale : Double = scale
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
        return (scale * scale * PI * PI) / 3.0
    }

    override fun instance(): Logistic {
        return Logistic(location, scale)
    }

    override fun cdf(x: Double): Double {
        val z = (x - location) / scale
        return 1.0 / (1.0 + exp(-z))
    }

    override fun pdf(x: Double): Double {
        val z = (x - location) / scale
        val num = exp(-z)
        val denom = scale * (1.0 + num) * (1.0 + num)
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
        val u = p / (1.0 - p)
        return location + scale * ln(u)
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

    override fun randomVariable(streamNumber: Int, streamProvider: RNStreamProviderIfc): LogisticRV {
        return LogisticRV(location, scale, streamNumber, streamProvider)
    }

    override fun toString(): String {
        return "Logistic(location=$location, scale=$scale)"
    }

    override val mean: Double
        get() = mean()
    override val variance: Double
        get() = variance()
    override val skewness: Double
        get() = 0.0
    override val kurtosis: Double
        get() = 6.0/5.0
}