package ksl.utilities.random.rvariable.parameters

import ksl.utilities.distributions.Distribution

interface CreateDistributionIfc {

    fun createDistribution() : Distribution
}