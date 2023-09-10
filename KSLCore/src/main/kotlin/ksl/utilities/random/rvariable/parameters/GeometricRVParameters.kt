package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GeometricRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class GeometricRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
        rvClassName = RVType.Geometric.parametrizedRVClass.simpleName!!
        rvType = (RVType.Geometric)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return GeometricRV(probOfSuccess, rnStream)
    }
}