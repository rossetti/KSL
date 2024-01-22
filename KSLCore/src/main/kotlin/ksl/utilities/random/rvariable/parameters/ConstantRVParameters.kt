package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class ConstantRVParameters : RVParameters(
    rvClassName = RVType.Constant.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Constant)
) {
    override fun fillParameters() {
        addDoubleParameter("value", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val value = doubleParameter("value")
        return ConstantRV(value)
    }
}