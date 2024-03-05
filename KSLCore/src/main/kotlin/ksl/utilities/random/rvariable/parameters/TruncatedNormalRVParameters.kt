package ksl.utilities.random.rvariable.parameters

import ksl.utilities.Interval
import ksl.utilities.distributions.TruncatedNormal
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TruncatedNormalRV

class TruncatedNormalRVParameters : RVParameters(
    rvClassName = RVType.TruncatedNormal.parametrizedRVClass.simpleName!!,
    rvType = (RVType.TruncatedNormal)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("mean", 0.0)
        addDoubleParameter("variance", 1.0)
        addDoubleParameter("lowerLimit", Double.NEGATIVE_INFINITY)
        addDoubleParameter("upperLimit", Double.POSITIVE_INFINITY)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        val lowerLimit = doubleParameter("lowerLimit")
        val upperLimit = doubleParameter("upperLimit")
        return TruncatedNormalRV(mean, variance, Interval(lowerLimit, upperLimit), rnStream)
    }

    override fun createDistribution(): TruncatedNormal {
        val mean = doubleParameter("mean")
        val variance = doubleParameter("variance")
        val lowerLimit = doubleParameter("lowerLimit")
        val upperLimit = doubleParameter("upperLimit")
        return TruncatedNormal(mean, variance, Interval(lowerLimit, upperLimit))
    }
}