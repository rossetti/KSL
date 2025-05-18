package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.JohnsonBRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class JohnsonBRVParameters : RVParameters(
    rvClassName = RVType.JohnsonB.parametrizedRVClass.simpleName!!,
    rvType = (RVType.JohnsonB)
) {
    override fun fillParameters() {
        addDoubleParameter("alpha1", 0.0)
        addDoubleParameter("alpha2", 1.0)
        addDoubleParameter("min", 0.0)
        addDoubleParameter("max", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val alpha1 = doubleParameter("alpha1")
        val alpha2 = doubleParameter("alpha2")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return JohnsonBRV(alpha1, alpha2, min, max, streamNumber, streamProvider)
    }
}