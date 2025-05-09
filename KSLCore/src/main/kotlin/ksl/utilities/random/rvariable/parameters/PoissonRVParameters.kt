package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Poisson
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.PoissonRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class PoissonRVParameters : RVParameters(
    rvClassName = RVType.Poisson.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Poisson)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("mean", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val mean = doubleParameter("mean")
        return PoissonRV(mean, streamNumber, streamProvider)
    }

    override fun createDistribution(): Poisson {
        val mean = doubleParameter("mean")
        return Poisson(mean)
    }
}