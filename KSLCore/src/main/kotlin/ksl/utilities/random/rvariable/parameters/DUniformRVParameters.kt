package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.DUniform
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class DUniformRVParameters : RVParameters(), CreateDistributionIfc<DUniform> {
    override fun fillParameters() {
        addIntegerParameter("min", 0)
        addIntegerParameter("max", 1)
        rvClassName = RVType.DUniform.parametrizedRVClass.simpleName!!
        rvType = (RVType.DUniform)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val min = integerParameter("min")
        val max = integerParameter("max")
        return DUniformRV(min, max, rnStream)
    }

    override fun createDistribution(): DUniform {
        val min = integerParameter("min")
        val max = integerParameter("max")
        return DUniform(min, max)
    }
}