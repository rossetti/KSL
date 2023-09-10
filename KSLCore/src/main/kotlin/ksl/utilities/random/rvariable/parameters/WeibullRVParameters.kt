package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.WeibullRV

class WeibullRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("shape", 1.0)
        addDoubleParameter("scale", 1.0)
        rvClassName = RVType.Weibull.parametrizedRVClass.simpleName!!
        rvType = (RVType.Weibull)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return WeibullRV(shape, scale, rnStream)
    }
}