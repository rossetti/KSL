package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GeneralizedBetaRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class GeneralizedBetaRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("alpha1", 1.0)
        addDoubleParameter("alpha2", 1.0)
        addDoubleParameter("min", 0.0)
        addDoubleParameter("max", 1.0)
        rvClassName = RVType.GeneralizedBeta.parametrizedRVClass.simpleName!!
        rvType = (RVType.GeneralizedBeta)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val alpha1 = doubleParameter("alpha1")
        val alpha2 = doubleParameter("alpha2")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return GeneralizedBetaRV(alpha1, alpha2, min, max, rnStream)
    }
}