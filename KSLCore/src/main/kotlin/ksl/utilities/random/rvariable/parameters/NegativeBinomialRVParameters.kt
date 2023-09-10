package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.NegativeBinomialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class NegativeBinomialRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        addIntegerParameter("numSuccesses", 1)
        rvClassName = RVType.NegativeBinomial.parametrizedRVClass.simpleName!!
        rvType = (RVType.NegativeBinomial)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        val numSuccesses = doubleParameter("numSuccesses")
        return NegativeBinomialRV(probOfSuccess, numSuccesses, rnStream)
    }
}