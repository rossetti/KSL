package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.BetaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class BetaRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("alpha1", 1.0)
        addDoubleParameter("alpha2", 1.0)
        rvClassName = RVType.Beta.parametrizedRVClass.simpleName!!
        rvType = (RVType.Beta)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val alpha1 = doubleParameter("alpha1")
        val alpha2 = doubleParameter("alpha2")
        return BetaRV(alpha1, alpha2, rnStream)
    }
}