package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.PearsonType5
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.PearsonType5RV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class PearsonType5RVParameters : RVParameters(
    rvClassName = RVType.PearsonType5.parametrizedRVClass.simpleName!!,
    rvType = (RVType.PearsonType5)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("shape", 1.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return PearsonType5RV(shape, scale, streamNumber, streamProvider)
    }

    override fun createDistribution(): PearsonType5 {
        val scale = doubleParameter("scale")
        val shape = doubleParameter("shape")
        return PearsonType5(shape, scale)
    }
}