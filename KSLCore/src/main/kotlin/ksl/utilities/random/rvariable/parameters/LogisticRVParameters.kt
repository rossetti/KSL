package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Distribution
import ksl.utilities.distributions.Logistic
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.LogisticRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class LogisticRVParameters : RVParameters(
    rvClassName = RVType.Logistic.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Logistic)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("location", 0.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val location = doubleParameter("location")
        val scale = doubleParameter("scale")
        return LogisticRV(location, scale, rnStream)
    }

    override fun createDistribution(): Logistic {
        val location = doubleParameter("location")
        val scale = doubleParameter("scale")
        return Logistic(location, scale)
    }
}