package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class NormalRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("mean", 0.0)
        addDoubleParameter("variance", 1.0)
        rvClassName = RVType.Normal.parametrizedRVClass.simpleName!!
        rvType = (RVType.Normal)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        return NormalRV(mean, variance, rnStream)
    }
}