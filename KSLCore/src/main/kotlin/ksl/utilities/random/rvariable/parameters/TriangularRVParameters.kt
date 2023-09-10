package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Triangular
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV

class TriangularRVParameters : RVParameters(), CreateDistributionIfc<Triangular> {
    override fun fillParameters() {
        addDoubleParameter("min", 0.0)
        addDoubleParameter("mode", 0.5)
        addDoubleParameter("max", 1.0)
        rvType = (RVType.Triangular)
        rvClassName = RVType.Triangular.parametrizedRVClass.simpleName!!
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mode = doubleParameter("mode")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return TriangularRV(min, mode, max, rnStream)
    }

    override fun createDistribution(): Triangular {
        val mode = doubleParameter("mode")
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return Triangular(min, mode, max)
    }
}