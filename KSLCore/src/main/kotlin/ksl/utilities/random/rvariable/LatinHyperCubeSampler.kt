package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.random.permute
import ksl.utilities.random.rng.RNStreamProviderIfc

class LatinHyperCubeSampler(
    val pointsPerDimension: Int,
    val intervals: List<Interval>,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name) {

    init {
        require(pointsPerDimension > 0) { "The number of points per dimension must be greater than zero!" }
        require(intervals.isNotEmpty()) { "The intervals must not be empty!" }
        for (interval in intervals) {
            require(interval.lowerLimit.isFinite()) { "The interval must be finite. The lower limit was -Infinity." }
            require(interval.upperLimit.isFinite()) { "The interval must be finite. The upper limit was +Infinity." }
            require(interval.lowerLimit != interval.upperLimit) { "The interval's width must be greater than zero!" }
        }
        generateCachedPoints()
    }

    override val dimension: Int
        get() = intervals.size

    val width = 1.0 / pointsPerDimension

    private val myCachedPoints = Array(pointsPerDimension) { DoubleArray(dimension) { 0.0 } }

    private var myCurrentPoint = 0

    private fun generateCachedPoints() {
        val myTmp = DoubleArray(pointsPerDimension)
        for (i in 0 until dimension) {
            for (j in 0 until pointsPerDimension) {
                myTmp[j] = rnStream.rUniform(j * width, (j + 1) * width)
            }
            myTmp.permute(rnStream)
            for (j in 0 until pointsPerDimension) {
                myCachedPoints[i][j] = myTmp[j]
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

}