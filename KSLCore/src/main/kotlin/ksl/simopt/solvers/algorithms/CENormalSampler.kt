package ksl.simopt.solvers.algorithms

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

class CENormalSampler(
    override val dimension: Int,
    initialMean: DoubleArray = DoubleArray(dimension) { 0.0 },
    initialSd: DoubleArray = DoubleArray(dimension) { 1.0 },
    meanSmoother: Double = 1.0,
    sdSmoother: Double = 1.0,
    sdThreshold: Double = 0.001,
    streamNum: Int = 0,
    val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : CESamplerIfc {
    init {
        require(dimension > 0) { "Dimension must be greater than zero." }
        require(initialMean.size == dimension) { "Mean vector must have length equal to the dimension." }
        require(initialSd.size == dimension) { "Standard deviation vector must have length equal to the dimension." }
        require(meanSmoother > 0) { "Mean smoother must be greater than zero." }
        require(sdSmoother > 0) { "Standard deviation smoother must be greater than zero." }
        require(sdThreshold > 0) { "Standard deviation threshold must be greater than zero." }
        require(meanSmoother <= 1) { "Mean smoother must be less than or equal to one." }
        require(sdSmoother <= 1) { "Standard deviation smoother must be less than or equal to one." }
        require(sdThreshold > 0) { "Standard deviation threshold must be greater than zero." }
        for (i in initialMean.indices) {
            require(initialMean[i].isFinite()) { "Mean vector must contain only finite values." }
            require(initialSd[i].isFinite()) { "Standard deviation vector must contain only finite values." }
            require(initialSd[i] > 0) { "Standard deviation vector must contain only positive values." }
        }
    }

    private val mean: DoubleArray = initialMean.copyOf()
    private val sd: DoubleArray = initialSd.copyOf()

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
    val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    /**
     *  The assigned stream number for the generation process
     */
    val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "Array must have length equal to the dimension." }
        for (i in array.indices) {
            array[i] = rnStream.rNormal(mean[i], sd[i]*sd[i])
        }
    }

    override fun updateParameters(elites: List<DoubleArray>) {
        TODO("Not yet implemented")
    }

    override fun parameters(): DoubleArray {
        return mean.copyOf()
    }

    override fun hasConverged(): Boolean {
        TODO("Not yet implemented")
    }


}