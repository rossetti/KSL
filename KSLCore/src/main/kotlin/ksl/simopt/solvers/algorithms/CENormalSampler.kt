package ksl.simopt.solvers.algorithms

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic
import kotlin.isFinite

class CENormalSampler(
    override val problemDefinition: ProblemDefinition,
    meanSmoother: Double = defaultMeanSmoother,
    sdSmoother: Double = defaultStdDevSmoother,
    sdThreshold: Double = defaultStdDevThreshold,
    streamNum: Int = 0,
    override val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : CESamplerIfc {

    override val dimension: Int = problemDefinition.inputSize

    init {
        require(meanSmoother > 0) { "Mean smoother must be greater than zero." }
        require(meanSmoother <= 1) { "Mean smoother must be less than or equal to one." }
        require(sdSmoother > 0) { "Standard deviation smoother must be greater than zero." }
        require(sdSmoother <= 1) { "Standard deviation smoother must be less than or equal to one." }
        require(sdThreshold > 0) { "Standard deviation threshold must be greater than zero." }
    }

    private val mean: DoubleArray = DoubleArray(dimension) { 1.0 }
    private val sd: DoubleArray = DoubleArray(dimension) { 1.0 }
    private val stats = List(dimension) { Statistic() }

    init {
        initializeParameters(problemDefinition.inputMidPoints)
    }

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
     *  This can be used to increase/decrease the variability associated with the initial parameter setting.
     *  For example, a value of 1.1 increases the starting standard deviation by 10%. The setting
     *  must be a positive value.  The default value is specified by [defaultVariabilityFactor].
     */
    var variabilityFactor: Double = defaultVariabilityFactor
        set(value) {
            require(value > 0) { "The default variance factor must be greater than zero." }
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

    override fun initializeParameters(values: DoubleArray) {
        require(values.size == dimension) { "The size of the parameters array must be equal to the dimension." }
        // first assign the means
        for (i in values.indices) {
            require(values[i].isFinite()) { "Mean vector must contain only finite values." }
            mean[i] = values[i]
            stats[i].reset()
        }
        // now assign the standard deviations
        val ranges = problemDefinition.inputRanges
        for (i in values.indices) {
            sd[i] = (ranges[i] / 4.0) * variabilityFactor
            require(sd[i] > sdThreshold) { "The initial standard deviation for parameter (${problemDefinition.inputNames[i]}) was set less than the stopping standard deviation threshold ($sdThreshold)." }
        }
    }

    override fun updateParameters(elites: List<DoubleArray>) {
        // estimate the mean and standard deviation for the elite samples for each dimension
        stats.forEach { it.reset() }
        for (array in elites) {
            require(array.size == dimension) { "The arrays within the elite sample must have length equal to the dimension." }
            for (i in array.indices) {
                stats[i].collect(array[i])
            }
        }
        // update the parameter vectors based on smoothing
        for (i in mean.indices) {
            mean[i] = meanSmoother * mean[i] + (1.0 - meanSmoother) * stats[i].average
            sd[i] = sdSmoother * sd[i] + (1.0 - sdSmoother) * stats[i].standardDeviation
        }
    }

    override fun parameters(): DoubleArray {
        return mean.copyOf()
    }

    /**
     *  The sampler is considered to be converged if the maximum of the underlying standard deviations
     *  is less than or equal to [sdThreshold]
     */
    override fun hasConverged(): Boolean {
        return sd.max() <= sdThreshold
    }

    companion object {

        /**
         *  This can be used to globally increase/decrease the variability associated with the initial parameter setting.
         *  For example, a value of 1.1 increases the starting standard deviation by 10%. The setting
         *  must be a positive value.  The default value is specified as 1.0.
         */
        var defaultVariabilityFactor: Double = 1.0
            set(value) {
                require(value > 0) { "The default variability factor must be greater than zero." }
                field = value
            }

        var defaultMeanSmoother: Double = 0.85
            set(value) {
                require(value > 0) { "Mean smoother must be greater than zero." }
                require(value <= 1) { "Mean smoother must be less than or equal to one." }
                field = value
            }

        var defaultStdDevSmoother: Double = 0.85
            set(value) {
                require(value > 0) { "Standard deviation smoother must be greater than zero." }
                require(value <= 1) { "Standard deviation smoother must be less than or equal to one." }
            }

        var defaultStdDevThreshold: Double = 0.001
            set(value) {
                require(value > 0) { "Standard deviation threshold must be greater than zero." }
                field = value
            }
    }

}
