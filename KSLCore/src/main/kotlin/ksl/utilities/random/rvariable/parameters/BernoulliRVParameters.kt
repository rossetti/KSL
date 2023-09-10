package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class BernoulliRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        rvClassName = RVType.Bernoulli.parametrizedRVClass.simpleName!!
        rvType = (RVType.Bernoulli)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return BernoulliRV(probOfSuccess, rnStream)
    }
}