package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Bernoulli
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class BernoulliRVParameters : RVParameters(
    rvClassName = RVType.Bernoulli.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Bernoulli)
), CreateDistributionIfc{
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return BernoulliRV(probOfSuccess, streamNumber, streamProvider)
    }

    override fun createDistribution(): Bernoulli {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return Bernoulli(probOfSuccess)
    }
}