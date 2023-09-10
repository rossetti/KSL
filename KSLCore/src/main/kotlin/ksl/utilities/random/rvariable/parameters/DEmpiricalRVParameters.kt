package ksl.utilities.random.rvariable.parameters

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class DEmpiricalRVParameters : RVParameters() {
    override fun fillParameters() {
        addDoubleArrayParameter("values", doubleArrayOf(0.0, 1.0))
        addDoubleArrayParameter("cdf", doubleArrayOf(0.5, 1.0))
        rvClassName = RVType.DEmpirical.parametrizedRVClass.simpleName!!
        rvType = (RVType.DEmpirical)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val values = doubleArrayParameter("values")
        val cdf = doubleArrayParameter("cdf")
        return DEmpiricalRV(values, cdf, rnStream)
    }
}