package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ChiSquaredRV
import ksl.utilities.random.rvariable.RVariableIfc

class ChiSquaredDistribution(
    degreesOfFreedom: Double,
    name: String? = null
) : Distribution<ChiSquaredDistribution>(name), ContinuousDistributionIfc, InverseCDFIfc {

    init {
        require(degreesOfFreedom > 0) { "Degrees of Freedom must be >= 1" }
    }

    private val myGamma: Gamma = Gamma((degreesOfFreedom / 2.0), 2.0)

    var dof: Double
        get() = myGamma.shape
        set(value) {
            myGamma.shape = value
        }

    override fun cdf(x: Double): Double {
        return myGamma.cdf(x)
    }

    override fun pdf(x: Double): Double {
        return myGamma.pdf(x)
    }

    override fun mean(): Double {
        return myGamma.mean()
    }

    override fun variance(): Double {
        return myGamma.variance()
    }

    override fun domain(): Interval {
        return myGamma.domain()
    }

    override fun invCDF(p: Double): Double {
        return myGamma.invCDF(p)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return ChiSquaredRV(dof, stream)
    }

    override fun instance(): ChiSquaredDistribution {
        return ChiSquaredDistribution(dof)
    }

    override fun parameters(params: DoubleArray) {
        dof = params[0]
    }

    override fun parameters(): DoubleArray {
        return doubleArrayOf(dof)
    }
}