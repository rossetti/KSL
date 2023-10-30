package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Binomial
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  If the number of trials parameter (numTrials) is a double value,
 *  it will be truncated to the nearest integer value when creating
 *  the corresponding random variable or distribution.
 */
class BinomialRVParameters : RVParameters(), CreateDistributionIfc<Binomial> {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        addDoubleParameter("numTrials", 2.0)
        rvClassName = RVType.Binomial.parametrizedRVClass.simpleName!!
        rvType = (RVType.Binomial)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numTrials = doubleParameter("numTrials")
        return BinomialRV(probOfSuccess, numTrials.toInt(), rnStream)
    }

    override fun createDistribution(): Binomial {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numTrials = doubleParameter("numTrials")
        return Binomial(probOfSuccess, numTrials.toInt())
    }
}