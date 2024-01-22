package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Distribution
import ksl.utilities.distributions.Laplace
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.LaplaceRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LaplaceRVParameters : RVParameters(
    rvClassName = RVType.Laplace.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Laplace)
), CreateDistributionIfc<Laplace> {
    override fun fillParameters() {
        addDoubleParameter("location", 0.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val scale = doubleParameter("scale")
        val location = doubleParameter("location")
        return LaplaceRV(location, scale, rnStream)
    }

    override fun createDistribution(): Distribution<Laplace> {
        val location = doubleParameter("location")
        val scale = doubleParameter("scale")
        return Laplace(location, scale)
    }
}