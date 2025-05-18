package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
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

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return ShiftedGeometricRV(probOfSuccess, streamNumber, streamProvider)
    }
}