package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class DEmpiricalRVParameters : RVParameters(
    rvClassName = RVType.DEmpirical.parametrizedRVClass.simpleName!!,
    rvType = (RVType.DEmpirical)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleArrayParameter("values", doubleArrayOf(0.0, 1.0))
        addDoubleArrayParameter("cdf", doubleArrayOf(0.5, 1.0))
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val values = doubleArrayParameter("values")
        val cdf = doubleArrayParameter("cdf")
        return DEmpiricalRV(values, cdf, rnStream)
    }

    override fun createDistribution(): DEmpiricalCDF {
        val values = doubleArrayParameter("values")
        val cdf = doubleArrayParameter("cdf")
        return DEmpiricalCDF(values, cdf)
    }
}