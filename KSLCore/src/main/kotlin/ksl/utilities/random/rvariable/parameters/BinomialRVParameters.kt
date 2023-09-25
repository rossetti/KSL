package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Binomial
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class BinomialRVParameters : RVParameters(), CreateDistributionIfc<Binomial> {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        addIntegerParameter("numTrials", 2)
        rvClassName = RVType.Binomial.parametrizedRVClass.simpleName!!
        rvType = (RVType.Binomial)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numTrials = integerParameter("numTrials")
        return BinomialRV(probOfSuccess, numTrials, rnStream)
    }

    override fun createDistribution(): Binomial {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numTrials = integerParameter("numTrials")
        return Binomial(probOfSuccess, numTrials)
    }
}