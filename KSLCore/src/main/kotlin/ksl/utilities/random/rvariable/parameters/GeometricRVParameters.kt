package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Geometric
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GeometricRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

class GeometricRVParameters : RVParameters(
    rvClassName = RVType.Geometric.parametrizedRVClass.simpleName!!,
    rvType = (RVType.Geometric)
), CreateDistributionIfc<Geometric> {
    override fun fillParameters() {
        addDoubleParameter("probOfSuccess", 0.5)
    }

    override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return GeometricRV(probOfSuccess, rnStream)
    }

    override fun createDistribution(): Geometric {
        val probOfSuccess = doubleParameter("probOfSuccess")
        return Geometric(probOfSuccess)
    }
}