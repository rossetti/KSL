package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Distribution
import ksl.utilities.distributions.Laplace
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.LaplaceRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LaplaceRVParameters : RVParameters(
    rvClassName = RVType.Laplace.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Laplace)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("location", 0.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val scale = doubleParameter("scale")
        val location = doubleParameter("location")
        return LaplaceRV(location, scale, streamNumber, streamProvider)
    }

    override fun createDistribution(): Laplace {
        val location = doubleParameter("location")
        val scale = doubleParameter("scale")
        return Laplace(location, scale)
    }
}