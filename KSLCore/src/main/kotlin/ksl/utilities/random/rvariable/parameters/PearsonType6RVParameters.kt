package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.PearsonType6
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.PearsonType6RV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class PearsonType6RVParameters : RVParameters(), CreateDistributionIfc<PearsonType6> {
    override fun fillParameters() {
        addDoubleParameter("alpha1", 2.0)
        addDoubleParameter("alpha2", 3.0)
        addDoubleParameter("beta", 1.0)
        rvClassName = RVType.PearsonType6.parametrizedRVClass.simpleName!!
        rvType = (RVType.PearsonType6)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val alpha1 = doubleParameter("alpha1")
        val alpha2 = doubleParameter("alpha2")
        val beta = doubleParameter("beta")
        return PearsonType6RV(alpha1, alpha2, beta, rnStream)
    }

    override fun createDistribution(): PearsonType6 {
        val alpha1 = doubleParameter("alpha1")
        val alpha2 = doubleParameter("alpha2")
        val beta = doubleParameter("beta")
        return PearsonType6(alpha1, alpha2, beta)
    }
}