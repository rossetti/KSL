package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Beta
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.BetaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class BetaRVParameters : RVParameters(), CreateDistributionIfc<Beta>  {
    override fun fillParameters() {
        addDoubleParameter("alpha", 1.0)
        addDoubleParameter("beta", 1.0)
        rvClassName = RVType.Beta.parametrizedRVClass.simpleName!!
        rvType = (RVType.Beta)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val alpha = doubleParameter("alpha")
        val beta = doubleParameter("beta")
        return BetaRV(alpha, beta, rnStream)
    }

    override fun createDistribution(): Beta {
        val alpha = doubleParameter("alpha")
        val beta = doubleParameter("beta")
        return Beta(alpha, beta)
    }
}