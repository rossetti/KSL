package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class NormalRVParameters : RVParameters(
    rvClassName = RVType.Normal.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Normal)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("mean", 0.0)
        addDoubleParameter("variance", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        return NormalRV(mean, variance, rnStream)
    }

    override fun createDistribution(): Normal {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        return Normal(mean, variance)
    }
}