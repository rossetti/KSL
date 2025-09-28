package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.ShiftedRV

/**
 * This class models a continuous distribution that has been shifted by
 * a positive constant.
 * @param theShift the shift parameter. It must be greater than or equal to 0.0
 * @param distribution the distribution to be shifted.
 * @param name an optional name for the distribution
 */
class ShiftedContinuousDistribution(
    theShift: Double,
    private val distribution: ContinuousDistributionIfc,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc {

    init {
        require(theShift >= 0.0) { "The shift should not be < 0.0" }
    }

    var shift : Double = theShift
        set(value) {
            require(value >= 0.0) { "The shift should not be < 0.0" }
            field = value
        }

    override fun pdf(x: Double): Double {
        return distribution.pdf(x - shift)
    }

    override fun domain(): Interval {
        val d = distribution.domain()
        val dl = d.lowerLimit + shift
        val du = d.upperLimit + shift
        return Interval(dl, du)
    }

    override fun instance(): ContinuousDistributionIfc{
        return ShiftedContinuousDistribution(shift, distribution.instance())
    }

    /**
     * Sets the parameters of the shifted distribution
     * shift = param[0]
     * If supplied, the other elements of the array are used in setting the
     * parameters of the underlying distribution.  If only the shift is supplied
     * as a parameter, then the underlying distribution's parameters are not changed
     * (and do not need to be supplied)
     * @param params the shift as param[0]
     */
    override fun parameters(params: DoubleArray) {
        shift = params[0]
        if (params.size == 1) {
            return
        }
        val y = DoubleArray(params.size - 1)
        for (i in y.indices) {
            y[i] = params[i + 1]
        }
        distribution.parameters(y)
    }

    override fun cdf(x: Double): Double {
        return if (x < shift) {
            0.0
        } else {
            distribution.cdf(x - shift)
        }
    }

    override fun mean(): Double {
        return shift + distribution.mean()
    }

    /**
     * Gets the parameters for the shifted distribution
     * shift = parameter[0]
     * The other elements of the returned array are
     * the parameters of the underlying distribution
     */
    override fun parameters(): DoubleArray {
        val x = distribution.parameters()
        val y = DoubleArray(x.size + 1)
        y[0] = shift
        for (i in x.indices) {
            y[i + 1] = x[i]
        }
        return y
    }

    override fun variance(): Double {
        return distribution.variance()
    }

    override fun invCDF(p: Double): Double {
        return distribution.invCDF(p) + shift
    }

    override fun randomVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val rv = distribution.randomVariable(streamNumber, streamProvider)
        return ShiftedRV(shift, rv, streamNumber, streamProvider)
    }

    override fun toString(): String {
        return "$shift + $distribution"
    }

}