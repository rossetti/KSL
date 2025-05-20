package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProviderIfc


/**
 *  Defines a multi-variate uniform over the supplied intervals.
 *
 * @param intervals the intervals for each uniform dimension
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class MVIUniform @JvmOverloads constructor(
    val intervals: List<Interval>,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name) {

    private val myRV: MVIndependentMarginals

    init {
        val list = mutableListOf<RVariableIfc>()
        for (interval in intervals) {
            require(interval.lowerLimit.isFinite()) {"The interval must be finite. The lower limit was -Infinity."}
            require(interval.upperLimit.isFinite()) {"The interval must be finite. The upper limit was +Infinity."}
            list.add(UniformRV(interval.lowerLimit, interval.upperLimit, streamNum, streamProvider))
        }
        myRV = MVIndependentMarginals(list, streamNum, streamProvider)
    }

    override fun generate(array: DoubleArray) {
        myRV.sample(array)
    }

    override val dimension: Int
        get() = intervals.size

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): MVIUniform {
        return MVIUniform(intervals, streamNumber, rnStreamProvider)
    }
}