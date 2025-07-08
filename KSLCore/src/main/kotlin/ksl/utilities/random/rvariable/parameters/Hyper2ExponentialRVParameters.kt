package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.Hyper2ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class Hyper2ExponentialRVParameters : RVParameters (
    rvClassName = RVType.Laplace.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Laplace)
){
    override fun fillParameters() {
        addDoubleParameter("mixingProb", 0.5)
        addDoubleParameter("mean1", 1.0)
        addDoubleParameter("mean2", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val mean1 = doubleParameter("mean1")
        val mean2 = doubleParameter("mean2")
        val mixingProb = doubleParameter("mixingProb")
        return Hyper2ExponentialRV(mixingProb, mean1, mean2, streamNumber, streamProvider)
    }
}