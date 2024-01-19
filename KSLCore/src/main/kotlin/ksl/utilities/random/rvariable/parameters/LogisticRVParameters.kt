package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.LogLogisticRV
import ksl.utilities.random.rvariable.LogisticRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LogisticRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("location", 0.0)
        addDoubleParameter("scale", 1.0)
        rvClassName = RVType.Logistic.parametrizedRVClass.simpleName!!
        rvType = (RVType.Logistic)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val location = doubleParameter("location")
        val scale = doubleParameter("scale")
        return LogisticRV(location, scale, rnStream)
    }
}