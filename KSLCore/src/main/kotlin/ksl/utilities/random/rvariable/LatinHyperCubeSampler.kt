package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.random.permute
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  Creates a Latin hyper-cube sampler with each dimension divided into the specified number of points.
 *  The hyper-cube is formed from the specified intervals.
 *
 *  @param pointsPerDimension the number of divisions of the dimensions
 *  @param intervals the intervals forming the "cube"
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class LatinHyperCubeSampler @JvmOverloads constructor(
    val pointsPerDimension: Int,
    val intervals: List<Interval>,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name) {

    val width: Double

    private val myCachedPoints = Array(pointsPerDimension) { DoubleArray(dimension) { 0.0 } }

    private var myCurrentPoint = 0

    init {
        require(pointsPerDimension > 0) { "The number of points per dimension must be greater than zero!" }
        require(intervals.isNotEmpty()) { "The intervals must not be empty!" }
        for (interval in intervals) {
            require(interval.lowerLimit.isFinite()) { "The interval must be finite. The lower limit was -Infinity." }
            require(interval.upperLimit.isFinite()) { "The interval must be finite. The upper limit was +Infinity." }
            require(interval.lowerLimit < interval.upperLimit) { "The interval's width must be greater than zero!" }
        }
        width = 1.0/pointsPerDimension
        generateCachedPoints()
    }

    /**
     *  Creates a Latin hyper-cube sampler over the unit hyper-cube of the specified dimension.
     *
     *  @param pointsPerDimension the number of divisions of the dimensions
     *  @param dimension number of dimensions for the unit hyper-cube.
     * @param streamNum the random number stream number, defaults to 0, which means the next stream
     * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
     * @param name an optional name
     */
    @Suppress("unused")
    @JvmOverloads
    constructor(
        pointsPerDimension: Int,
        dimension: Int,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(pointsPerDimension, createZeroOneIntervals(dimension), streamNum, streamProvider, name)

    override val dimension: Int
        get() = intervals.size

    private fun generateCachedPoints() {
        val myTmp = DoubleArray(pointsPerDimension)
        for (i in 0 until dimension) {
            for (j in 0 until pointsPerDimension) {
                myTmp[j] = rnStream.rUniform(j * width, (j + 1) * width)
            }
            myTmp.permute(rnStream)
            for (j in 0 until pointsPerDimension) {
                myCachedPoints[j][i] = myTmp[j]
            }
        }
    }

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        val u = myCachedPoints[myCurrentPoint]
        for (i in 0 until dimension) {
            val ll = intervals[i].lowerLimit
            val ul = intervals[i].upperLimit
            array[i] = ll + (ul - ll) * u[i]
        }
        myCurrentPoint++
        if (myCurrentPoint == pointsPerDimension) {
            myCurrentPoint = 0
            generateCachedPoints()
        }
    }

    override fun instance(
        streamNumber: Int,
        rnStreamProvider: RNStreamProviderIfc
    ): LatinHyperCubeSampler {
        return LatinHyperCubeSampler(this.pointsPerDimension, this.intervals, streamNumber, streamProvider, name)
    }

    companion object {

        /**
         *  Creates [numIntervals] intervals over the range from 0 to 1.
         *  @param numIntervals the number of intervals. Must be at least 1.
         *  @return the list of created intervals
         */
        fun createZeroOneIntervals(numIntervals: Int): List<Interval> {
            require(numIntervals > 0) { "The number of intervals must be positive." }
            val list = mutableListOf<Interval>()
            val interval = Interval(0.0, 1.0)
            for(i in 0 until numIntervals) {
                list.add(interval)
                println(interval)
            }
            return list
        }
    }

}