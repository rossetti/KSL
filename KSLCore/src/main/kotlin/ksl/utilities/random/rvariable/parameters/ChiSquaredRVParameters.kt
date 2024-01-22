package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ChiSquaredRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class ChiSquaredRVParameters : RVParameters(
    rvClassName = RVType.ChiSquared.parametrizedRVClass.simpleName!!,
    rvType = (RVType.ChiSquared)
) {
    override fun fillParameters() {
        addDoubleParameter("dof", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val dof = doubleParameter("dof")
        return ChiSquaredRV(dof, rnStream)
    }
}