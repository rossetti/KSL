package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Triangular
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV

class TriangularRVParameters : RVParameters(
    rvClassName = RVType.Triangular.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Triangular)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("min", 0.0)
        addDoubleParameter("mode", 0.5)
        addDoubleParameter("max", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val mode = doubleParameter("mode")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return TriangularRV(min, mode, max, streamNumber, streamProvider)
    }

    override fun createDistribution(): Triangular {
        val mode = doubleParameter("mode")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return Triangular(min, mode, max)
    }
}