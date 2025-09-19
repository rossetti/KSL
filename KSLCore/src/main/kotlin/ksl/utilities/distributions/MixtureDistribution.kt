package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariableIfc


class MixtureDistribution(
    private val cdfList: List<ContinuousDistributionIfc>,
    cdf: DoubleArray,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc {

   // private val myDomain: Interval

    init {
        require(cdfList.size >= 2) {"The number of random variables in the list must be greater than or equal to two." }
        require(cdf.size == cdfList.size) { "The number of elements in the cdf must equal the number of elements in the list" }
        require(KSLRandom.isValidCDF(cdf)) { "The cdf was not a valid CDF" }
        var minLL = Double.MAX_VALUE
        var maxUL = -Double.MAX_VALUE
        for(cdf in cdfList) {
            val d = cdf.domain()
            val dLL = d.lowerLimit
            val dUL = d.upperLimit

        }


    }

    val cdf: DoubleArray = cdf.copyOf()
        get() = field.copyOf()

    override fun domain(): Interval {
        TODO("Not yet implemented")
    }

    override fun pdf(x: Double): Double {
        TODO("Not yet implemented")
    }

    override fun cdf(x: Double): Double {
        TODO("Not yet implemented")
    }

    override fun instance(): DistributionIfc {
        TODO("Not yet implemented")
    }

    override fun invCDF(p: Double): Double {
        TODO("Not yet implemented")
    }

    override fun mean(): Double {
        TODO("Not yet implemented")
    }

    override fun variance(): Double {
        TODO("Not yet implemented")
    }

    override fun parameters(params: DoubleArray) {
        TODO("Not yet implemented")
    }

    override fun parameters(): DoubleArray {
        TODO("Not yet implemented")
    }

    override fun randomVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        TODO("Not yet implemented")
    }




}