package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.LogLogisticRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LogLogisticRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("shape", 1.0)
        addDoubleParameter("scale", 1.0)
        rvClassName = RVType.LogLogistic.parametrizedRVClass.simpleName!!
        rvType = (RVType.LogLogistic)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return LogLogisticRV(shape, scale, rnStream)
    }
}