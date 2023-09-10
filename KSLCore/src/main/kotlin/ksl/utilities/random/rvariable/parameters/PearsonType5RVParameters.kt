package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.PearsonType5RV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class PearsonType5RVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("shape", 1.0)
        addDoubleParameter("scale", 1.0)
        rvClassName = RVType.PearsonType5.parametrizedRVClass.simpleName!!
        rvType = (RVType.PearsonType5)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return PearsonType5RV(shape, scale, rnStream)
    }
}