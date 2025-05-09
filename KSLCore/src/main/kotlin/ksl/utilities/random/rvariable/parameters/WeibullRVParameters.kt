package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Weibull
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.WeibullRV

class WeibullRVParameters : RVParameters(
    rvClassName = RVType.Weibull.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Weibull)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("shape", 1.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return WeibullRV(shape, scale, streamNumber, streamProvider)
    }

    override fun createDistribution(): Weibull {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return Weibull(shape, scale)
    }
}