package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Uniform
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.UniformRV

class UniformRVParameters : RVParameters(
    rvClassName = RVType.Uniform.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Uniform)
), CreateDistributionIfc<Uniform> {
    override fun fillParameters() {
        addDoubleParameter("min", 0.0)
        addDoubleParameter("max", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return UniformRV(min, max, rnStream)
    }

    override fun createDistribution(): Uniform {
        val min = doubleParameter("min")
        val max = doubleParameter("max")
        return Uniform(min, max)
    }
}