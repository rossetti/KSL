package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.random.rvariable.parameters.TruncatedNormalRVParameters

/**
 * A truncated normal distribution.
 *
 * @param mean the mean of the distribution
 * @param variance the variance of the distribution
 * @param interval the interval over which the distribution is defined
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class TruncatedNormalRV(
    val mean: Double = 0.0,
    val variance: Double = 1.0,
    interval: Interval,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNumber, streamProvider, name) {

    init {
        require(interval.contains(mean)){ "The normal mean value, $mean, was not within the truncation interval, $interval." }
    }

    private val myInterval = interval.instance()

    val interval
        get() = myInterval.instance()

    private val myTN = TruncatedRV(
        Normal(mean, variance),
        Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        interval.lowerLimit,
        interval.upperLimit, streamNumber,
        streamProvider, name
    )

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): TruncatedNormalRV {
        return TruncatedNormalRV(mean, variance, myInterval, streamNumber, rnStreamProvider, name)
    }

    override fun toString(): String {
        return "TruncatedNormalRV(mean=$mean, variance=$variance, interval=$myInterval)"
    }

    override fun generate(): Double {
        return myTN.sample()
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = TruncatedNormalRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            parameters.changeDoubleParameter("variance", variance)
            parameters.changeDoubleParameter("lowerLimit", myInterval.lowerLimit)
            parameters.changeDoubleParameter("upperLimit", myInterval.upperLimit)
            return parameters
        }

}