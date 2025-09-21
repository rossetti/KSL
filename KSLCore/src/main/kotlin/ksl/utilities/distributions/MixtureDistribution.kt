package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.MixtureRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  This class models mixture distributions that have continuous
 *  distributions as their components.
 *  @param cdfList the list of distributions to be mixed
 *  @param cdf the mixing distribution specified as a CDF
 *  @param name an optional name
 */
class MixtureDistribution(
    private val cdfList: List<ContinuousDistributionIfc>,
    cdf: DoubleArray,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc {

    private val myDomain: Interval
    private val myWeights: DoubleArray
    private val myCDF : DoubleArray

    /**
     *  The total number of parameters in the mixing distribution.
     *  Each weight counts as a parameter and the total number of
     *  parameters associated with the distributions.
     */
    val numParameters: Int

    init {
        require(cdfList.size >= 2) { "The number of random variables in the list must be greater than or equal to two." }
        require(cdf.size == cdfList.size) { "The number of elements in the cdf must equal the number of elements in the list" }
        require(KSLRandom.isValidCDF(cdf)) { "The cdf was not a valid CDF" }
        myCDF = cdf.copyOf()
        var minLL = Double.POSITIVE_INFINITY
        var maxUL = Double.NEGATIVE_INFINITY
        for (cdf in cdfList) {
            val d = cdf.domain()
            val dLL = d.lowerLimit
            val dUL = d.upperLimit
            if (dLL <= minLL) {
                minLL = dLL
            }
            if (dUL >= maxUL) {
                maxUL = dUL
            }
        }
        myDomain = Interval(minLL, maxUL)
        myWeights = computeWeights(cdf)
        numParameters = parameters().size
    }

    private fun computeWeights(incomingCDF: DoubleArray): DoubleArray {
        var cp = 0.0
        val list = mutableListOf<Double>()
        for ((i, _) in incomingCDF.withIndex()) {
            val pp = incomingCDF[i] - cp
            cp = incomingCDF[i]
            list.add(pp)
        }
        return list.toDoubleArray()
    }

    /**
     *  The mixing weights as a CDF
     */
    @Suppress("unused")
    val mixingCDF: DoubleArray
        get() = myCDF.copyOf()

    /**
     *  The mixing weights.
     */
    val weights: DoubleArray
        get() = myWeights.copyOf()

    /**
     *  The distributions that are mixed.
     */
    val distributions: List<ContinuousDistributionIfc>
        get() {
            val list = mutableListOf<ContinuousDistributionIfc>()
            for(distribution in cdfList){
                list.add(distribution.instance())
            }
            return list
        }

    override fun domain(): Interval {
        return myDomain.instance()
    }

    override fun pdf(x: Double): Double {
        if (x <= myDomain.lowerLimit) {
            return 0.0
        }
        if (x >= myDomain.upperLimit) {
            return 0.0
        }
        var sum = 0.0
        for ((i, v) in cdfList.withIndex()) {
            sum = sum + myWeights[i] * v.pdf(x)
        }
        return sum
    }

    override fun cdf(x: Double): Double {
        if (x <= myDomain.lowerLimit) {
            return 0.0
        }
        if (x >= myDomain.upperLimit) {
            return 1.0
        }
        var sum = 0.0
        for ((i, v) in cdfList.withIndex()) {
            sum = sum + myWeights[i] * v.cdf(x)
        }
        return sum
    }

    override fun instance(): MixtureDistribution {
        return MixtureDistribution(cdfList, myCDF, name)
    }

    override fun invCDF(p: Double): Double {
        require(p >= 0.0) { "The supplied probability was less than zero." }
        require(p <= 1.0) { "The supplied probability was greater than 1.0" }
        if (p <= 0.0) {
            return myDomain.lowerLimit
        }
        if (p >= 1.0) {
            return myDomain.upperLimit
        }
        // get the component quantiles
        val xq = cdfList.map { it.invCDF(p) }
        // find the bracket
        val lo = xq.min()
        val hi = xq.max()
        // do bi-section search within the brackets
        return inverseContinuousCDFViaBisection(this, p, lo, hi)
    }

    override fun mean(): Double {
        var sum = 0.0
        for ((i, v) in cdfList.withIndex()) {
            sum = sum + myWeights[i] * v.mean()
        }
        return sum
    }

    override fun variance(): Double {
        var sum = 0.0 // weighted average of 2nd moments
        val mu = mean()
        for ((j, v) in cdfList.withIndex()) {
            val muj = v.mean()
            val ex2j = v.variance() + muj * muj // 2nd moment
            sum = sum + myWeights[j] * ex2j
        }
        return sum - mu * mu
    }

    /**
     * This function sets the parameters of the distribution according to the supplied array.
     * The array must have the following form
     *  - first (cdf.size) elements of the array are the elements of the mixing CDF
     *  Then, in the order of the supplied list of distributions, the array
     *  holds in consecutive entries, the parameters of each distribution.
     *
     * @param params the array of parameters to process. The size of this array must be the size
     * of the array returned from the parameters() function.
     */
    override fun parameters(params: DoubleArray) {
        // get the current parameters to check size
        val paramArray = parameters()
        require(params.size == paramArray.size) { "The number of parameters in the array is not equal to the required number of parameters" }
        // assume the first myCDF.size elements are the CDF specified in the array.
        val paramList = params.toList() // All parameters
        // extract the incoming CDF
        val incomingCDF = paramList.take(myCDF.size)
        // recompute the weights
        val incomingWeights = computeWeights(incomingCDF.toDoubleArray())
        // copy into myCDF and myWeights
        for ((i, _) in myCDF.withIndex()) {
            myCDF[i] = incomingCDF[i]
            myWeights[i] = incomingWeights[i]
        }
        // get just the incoming parameters by removing the CDF values
        var incomingParams = paramList.drop(myCDF.size)
        // now need to set the parameters for each of the distributions
        for (distribution in cdfList) {
            // get the current parameters from the distribution
            val currentParameters = distribution.parameters()
            // get the incoming parameters for the distribution
            val newParameters = incomingParams.take(currentParameters.size)
            incomingParams = incomingParams.drop(currentParameters.size)
            // change the parameters for the distribution
            distribution.parameters(newParameters.toDoubleArray())
        }
    }

    /**
     * This function returns the parameters of the mixture distribution as an array.
     * The array will have the following form
     *  - first (cdf.size) elements of the array are the elements of the mixing CDF
     *  Then, in the order of the supplied list of distributions, the array will
     *  hold, in consecutive entries, the parameters of each distribution.
     *
     * @return the mixing CDF and distribution parameters as an array.
     */
    override fun parameters(): DoubleArray {
        val list = mutableListOf<Double>()
        // first the cdf
        for (cp in myCDF) {
            list.add(cp)
        }
        // now the parameters for each distribution
        for (cd in cdfList) {
            val pArray = cd.parameters()
            for (p in pArray) {
                list.add(p)
            }
        }
        return list.toDoubleArray()
    }

    /**
     *  This function returns the parameters of the mixture distribution as
     *  a list of double arrays.  The first array is the CDF of the mixture.
     *  Then, each parameter array from the list of supplied distributions.
     */
    @Suppress("unused")
    fun parameterArrays() : List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        list.add(myCDF)
        for (cd in cdfList) {
            val pArray = cd.parameters()
            list.add(pArray)
        }
        return list
    }

    override fun randomVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): MixtureRV {
        val list = mutableListOf<RVariableIfc>()
        for (v in cdfList) {
            list.add(v.randomVariable(streamNumber, streamProvider))
        }
        return MixtureRV(list, myCDF, streamNumber, streamProvider)
    }

}
