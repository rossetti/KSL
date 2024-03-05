package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Gamma
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class GammaRVParameters : RVParameters(
    rvClassName = RVType.Gamma.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Gamma)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("shape", 1.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return GammaRV(shape, scale, rnStream)
    }

    override fun createDistribution(): Gamma {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return Gamma(shape, scale)
    }
}