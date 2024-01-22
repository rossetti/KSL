package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.NegativeBinomialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.floor

/**
 *  Since the number of successes parameter (numSuccesses) is a double value,
 *  it will be rounded up to the nearest integer which can be equal to or below
 *  the actual value when creating the corresponding random variable or distribution.
 */
class NegativeBinomialRVParameters : RVParameters(
    rvClassName = RVType.NegativeBinomial.parametrizedRVClass.simpleName!!,
    rvType = (RVType.NegativeBinomial)
), CreateDistributionIfc<NegativeBinomial> {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        addDoubleParameter("numSuccesses", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numSuccesses = doubleParameter("numSuccesses")
        return NegativeBinomialRV(probOfSuccess, floor(numSuccesses), rnStream)
    }

    override fun createDistribution(): NegativeBinomial {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numSuccesses = doubleParameter("numSuccesses")
        return NegativeBinomial(probOfSuccess, floor(numSuccesses))
    }
}