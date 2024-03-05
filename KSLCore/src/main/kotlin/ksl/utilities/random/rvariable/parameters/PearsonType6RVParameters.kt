package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.PearsonType6
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.PearsonType6RV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class PearsonType6RVParameters : RVParameters(
    rvClassName = RVType.PearsonType6.parametrizedRVClass.simpleName!!,
    rvType = (RVType.PearsonType6)
), CreateDistributionIfc {
    override fun fillParameters() {
        addDoubleParameter("shape1", 2.0)
        addDoubleParameter("shape2", 3.0)
        addDoubleParameter("scale", 1.0)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val alpha1 = doubleParameter("shape1")
        val alpha2 = doubleParameter("shape2")
        val beta = doubleParameter("scale")
        return PearsonType6RV(alpha1, alpha2, beta, rnStream)
    }

    override fun createDistribution(): PearsonType6 {
        val alpha1 = doubleParameter("shape1")
        val alpha2 = doubleParameter("shape2")
        val beta = doubleParameter("scale")
        return PearsonType6(alpha1, alpha2, beta)
    }
}