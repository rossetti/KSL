package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Poisson
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.PoissonRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class PoissonRVParameters : RVParameters(), CreateDistributionIfc<Poisson> {
    override fun fillParameters() {
        addDoubleParameter("mean", 1.0)
        rvClassName = RVType.Poisson.parametrizedRVClass.simpleName!!
        rvType = (RVType.Poisson)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mean = doubleParameter("mean")
        return PoissonRV(mean, rnStream)
    }

    override fun createDistribution(): Poisson {
        val mean = doubleParameter("mean")
        return Poisson(mean)
    }
}