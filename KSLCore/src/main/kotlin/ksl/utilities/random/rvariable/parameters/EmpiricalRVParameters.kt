package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.EmpiricalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class EmpiricalRVParameters : RVParameters(
    rvClassName = RVType.Empirical.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Empirical)
) {
    override fun fillParameters() {
        addDoubleArrayParameter("population", DoubleArray(1))
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val population = doubleArrayParameter("population")
        return EmpiricalRV(population, streamNumber, streamProvider)
    }
}