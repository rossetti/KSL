package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.NegativeBinomialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class NegativeBinomialRVParameters : RVParameters(), CreateDistributionIfc<NegativeBinomial> {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        addDoubleParameter("numSuccesses", 1.0)
        rvClassName = RVType.NegativeBinomial.parametrizedRVClass.simpleName!!
        rvType = (RVType.NegativeBinomial)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numSuccesses = doubleParameter("numSuccesses")
        return NegativeBinomialRV(probOfSuccess, numSuccesses, rnStream)
    }

    override fun createDistribution(): NegativeBinomial {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numSuccesses = doubleParameter("numSuccesses")
        return NegativeBinomial(probOfSuccess, numSuccesses)
    }
}