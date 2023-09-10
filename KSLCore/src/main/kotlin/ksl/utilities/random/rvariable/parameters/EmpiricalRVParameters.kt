package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.EmpiricalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class EmpiricalRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleArrayParameter("population", DoubleArray(1))
        rvClassName = RVType.Empirical.parametrizedRVClass.simpleName!!
        rvType = (RVType.Empirical)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val population = doubleArrayParameter("population")
        return EmpiricalRV(population, rnStream)
    }
}