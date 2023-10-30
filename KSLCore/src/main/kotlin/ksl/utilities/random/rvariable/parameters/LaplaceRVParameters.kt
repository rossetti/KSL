package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.LaplaceRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LaplaceRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleParameter("mean", 0.0)
        addDoubleParameter("scale", 1.0)
        rvClassName = RVType.Laplace.parametrizedRVClass.simpleName!!
        rvType = (RVType.Laplace)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val scale = doubleParameter("scale")
        val mean = doubleParameter("mean")
        return LaplaceRV(mean, scale, rnStream)
    }
}