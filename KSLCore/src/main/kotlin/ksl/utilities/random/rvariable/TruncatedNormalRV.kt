package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.random.rvariable.parameters.TruncatedNormalRVParameters

class TruncatedNormalRV(
    val mean: Double = 0.0,
    val variance: Double = 1.0,
    interval: Interval,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {

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
        interval.upperLimit,
        stream
    )

    constructor(mean: Double, variance: Double, interval: Interval, streamNum: Int) :
            this(mean, variance, interval, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): TruncatedNormalRV {
        return TruncatedNormalRV(mean, variance, myInterval, stream)
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