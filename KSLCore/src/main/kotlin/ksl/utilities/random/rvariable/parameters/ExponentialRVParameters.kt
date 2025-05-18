package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Exponential
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class ExponentialRVParameters : RVParameters(
    rvClassName = RVType.Exponential.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Exponential)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("mean", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val mean = doubleParameter("mean")
        return ExponentialRV(mean, streamNumber, streamProvider)
    }

    override fun createDistribution(): Exponential {
        val mean = doubleParameter("mean")
        return Exponential(mean)
    }
}