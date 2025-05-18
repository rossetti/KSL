package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Binomial
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  If the number of trials parameter (numTrials) is a double value,
 *  it will be truncated to the nearest integer value when creating
 *  the corresponding random variable or distribution.
 */
class BinomialRVParameters : RVParameters(
    rvClassName = RVType.Binomial.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Binomial)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        addDoubleParameter("numTrials", 2.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numTrials = doubleParameter("numTrials")
        return BinomialRV(probOfSuccess, numTrials.toInt(), streamNumber, streamProvider)
    }

    override fun createDistribution(): Binomial {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numTrials = doubleParameter("numTrials")
        return Binomial(probOfSuccess, numTrials.toInt())
    }
}