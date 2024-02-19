package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.GeneralizedBeta
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GeneralizedBetaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class GeneralizedBetaRVParameters : RVParameters(
    rvClassName = RVType.GeneralizedBeta.parametrizedRVClass.simpleName!!,
    rvType = (RVType.GeneralizedBeta)
), CreateDistributionIfc<GeneralizedBeta> {
    override fun fillParameters() {
        addDoubleParameter("alpha", 1.0)
        addDoubleParameter("beta", 1.0)
        addDoubleParameter("min", 0.0)
        addDoubleParameter("max", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val alpha = doubleParameter("alpha")
        val beta = doubleParameter("beta")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return GeneralizedBetaRV(alpha, beta, min, max, rnStream)
    }

    override fun createDistribution(): GeneralizedBeta {
        val alpha = doubleParameter("alpha")
        val beta = doubleParameter("beta")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return GeneralizedBeta(alpha, beta, min, max)
    }
}