package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.distributions.Distribution
import ksl.utilities.distributions.PWCEmpiricalCDF
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.PWCEmpiricalRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Defines the parameters for a piecewise constant empirical random variable (PWCEmpiricalRV)
 * used in creating instances of random variables that follow a piecewise constant empirical distribution.
 *
 * This class extends RVParameters and specifies the required parameters for the
 * PWCEmpiricalRV, including breakpoints and their associated proportions, which describe
 * the empirical distribution.
 *
 * The `fillParameters` method initializes the default values for the breakpoints and
 * proportions arrays. These can be modified as needed before the random variable is created.
 *
 * The `createRVariable` method creates an instance of PWCEmpiricalRV using the configured
 * parameters and provides it with the specified random stream.
 **/
class PWCEmpiricalRVParameters : RVParameters(
    rvClassName = RVType.PWCEmpirical.parametrizedRVClass.simpleName!!,
    rvType = (RVType.PWCEmpirical)
) , CreateDistributionIfc{
    override fun fillParameters() {
        addDoubleArrayParameter("breakPoints", doubleArrayOf(0.0, 1.0, 2.0))
        addDoubleArrayParameter("proportions", doubleArrayOf(0.5, 0.5))
    }

    override fun createRVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        val breakPoints = doubleArrayParameter("breakPoints")
        val proportions = doubleArrayParameter("proportions")
        return PWCEmpiricalRV(breakPoints, proportions, streamNumber, streamProvider)
    }

    override fun createDistribution(): PWCEmpiricalCDF {
        val breakPoints = doubleArrayParameter("breakPoints")
        val proportions = doubleArrayParameter("proportions")
        return PWCEmpiricalCDF(breakPoints, proportions)
    }

}