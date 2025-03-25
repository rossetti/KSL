package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc


/**
 *  Defines a multi-variate uniform over the supplied intervals.
 */
class MVIUniform(
    val intervals: List<Interval>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariable(stream) {

    private val myRV: MVIndependentMarginals

    init {
        val list = mutableListOf<RVariableIfc>()
        for (interval in intervals) {
            require(interval.lowerLimit.isFinite()) {"The interval must be finite. The lower limit was -Infinity."}
            require(interval.upperLimit.isFinite()) {"The interval must be finite. The upper limit was +Infinity."}
            list.add(UniformRV(interval.lowerLimit, interval.upperLimit, stream))
        }
        myRV = MVIndependentMarginals(list, stream)
    }

    override fun generate(array: DoubleArray) {
        myRV.sample(array)
    }

    override fun instance(stream: RNStreamIfc): MVIUniform {
       return MVIUniform(intervals, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return myRV.antitheticInstance()
    }

    override val dimension: Int
        get() = intervals.size
}