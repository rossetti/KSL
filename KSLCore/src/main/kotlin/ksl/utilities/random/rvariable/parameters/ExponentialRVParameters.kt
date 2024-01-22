package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Exponential
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class ExponentialRVParameters : RVParameters(
    rvClassName = RVType.Exponential.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Exponential)
), CreateDistributionIfc<Exponential> {
    override fun fillParameters() {
        addDoubleParameter("mean", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mean = doubleParameter("mean")
        return ExponentialRV(mean, rnStream)
    }

    override fun createDistribution(): Exponential {
        val mean = doubleParameter("mean")
        return Exponential(mean)
    }
}