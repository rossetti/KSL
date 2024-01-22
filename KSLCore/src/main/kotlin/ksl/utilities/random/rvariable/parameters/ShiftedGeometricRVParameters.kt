package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.ShiftedGeometricRV

class ShiftedGeometricRVParameters : RVParameters(
    rvClassName = RVType.ShiftedGeometric.parametrizedRVClass.simpleName!!,
    rvType = (RVType.ShiftedGeometric)
) {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return ShiftedGeometricRV(probOfSuccess, rnStream)
    }
}