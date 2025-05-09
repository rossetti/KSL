package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Beta
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.BetaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class BetaRVParameters : RVParameters(
    rvClassName = RVType.Beta.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Beta)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("alpha", 1.0)
        addDoubleParameter("beta", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val alpha = doubleParameter("alpha")
        val beta = doubleParameter("beta")
        return BetaRV(alpha, beta, streamNumber, streamProvider)
    }

    override fun createDistribution(): Beta {
        val alpha = doubleParameter("alpha")
        val beta = doubleParameter("beta")
        return Beta(alpha, beta)
    }
}