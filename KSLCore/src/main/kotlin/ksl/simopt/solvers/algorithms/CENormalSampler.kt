package ksl.simopt.solvers.algorithms

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import kotlin.isFinite

class CENormalSampler(
    override val dimension: Int,
    meanSmoother: Double = 1.0,
    sdSmoother: Double = 1.0,
    sdThreshold: Double = 0.001,
    streamNum: Int = 0,
    override val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : CESamplerIfc {
    init {
        require(dimension > 0) { "Dimension must be greater than zero." }
        require(meanSmoother > 0) { "Mean smoother must be greater than zero." }
        require(meanSmoother <= 1) { "Mean smoother must be less than or equal to one." }
        require(sdSmoother > 0) { "Standard deviation smoother must be greater than zero." }
        require(sdSmoother <= 1) { "Standard deviation smoother must be less than or equal to one." }
        require(sdThreshold > 0) { "Standard deviation threshold must be greater than zero." }
    }

    private val mean: DoubleArray = DoubleArray(dimension) { 1.0 }
    private val sd: DoubleArray = DoubleArray(dimension) { 10.0 }

    var meanSmoother: Double = meanSmoother
        set(value) {
            require(value > 0) { "Mean smoother must be greater than zero." }
            require(value <= 1) { "Mean smoother must be less than or equal to one." }
            field = value
        }

    var sdSmoother: Double = sdSmoother
        set(value) {
            require(value > 0) { "Standard deviation smoother must be greater than zero." }
            require(value <= 1) { "Standard deviation smoother must be less than or equal to one." }
        }

    var sdThreshold: Double = sdThreshold
        set(value) {
            require(value > 0) { "Standard deviation threshold must be greater than zero." }
            field = value
        }

    /**
     * rnStream provides a reference to the underlying stream of random numbers.
     */
    override val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    /**
     *  The assigned stream number for the generation process
     */
    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "Array must have length equal to the dimension." }
        for (i in array.indices) {
            array[i] = rnStream.rNormal(mean[i], sd[i] * sd[i])
        }
    }

    /**
     * @param parameters the supplied parameter array must be size 2*d, where
     * d is the dimension. The first d elements represent the mean values and the second
     * d values represent the standard deviations. All elements must be finite and the
     * standard deviations must all be strictly positive.
     */
    override fun initialize(parameters: DoubleArray) {
        require(parameters.size == 2 * dimension) { "The size of the parameters array must be 2*dimension = (${2 * dimension})" }
        val m = parameters.copyOfRange(0, dimension - 1)
        val s = parameters.copyOfRange(dimension, 2 * dimension - 1)
        require(m.size == dimension) { "Mean vector must have length equal to the dimension." }
        require(s.size == dimension) { "Standard deviation vector must have length equal to the dimension." }
        for (i in m.indices) {
            require(m[i].isFinite()) { "Mean vector must contain only finite values." }
            require(s[i].isFinite()) { "Standard deviation vector must contain only finite values." }
            require(s[i] > 0) { "Standard deviation vector must contain only positive values." }
            mean[i] = m[i]
            sd[i] = s[i]
        }
    }

    override fun updateParameters(elites: List<DoubleArray>) {
        TODO("Not yet implemented")
    }

    override fun solution(): DoubleArray {
        return mean.copyOf()
    }

    override fun hasConverged(): Boolean {
        TODO("Not yet implemented")
    }


}