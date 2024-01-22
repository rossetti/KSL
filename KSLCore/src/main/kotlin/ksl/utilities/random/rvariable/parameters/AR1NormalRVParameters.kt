package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.AR1NormalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class AR1NormalRVParameters : RVParameters(
    rvClassName = RVType.AR1Normal.parametrizedRVClass.simpleName!!,
    rvType = (RVType.AR1Normal)
) {
    override fun fillParameters() {
        addDoubleParameter("mean", 0.0)
        addDoubleParameter("variance", 1.0)
        addDoubleParameter("correlation", 0.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        val correlation = doubleParameter("variance")
        return AR1NormalRV(mean, variance, correlation, rnStream)
    }
}