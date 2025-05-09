package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Lognormal
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LognormalRVParameters() : RVParameters(
    rvClassName = RVType.Lognormal.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Lognormal)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("mean", 1.0)
        addDoubleParameter("variance", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        return LognormalRV(mean, variance, streamNumber, streamProvider)
    }

    override fun createDistribution(): Lognormal {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        return Lognormal(mean, variance)
    }
}