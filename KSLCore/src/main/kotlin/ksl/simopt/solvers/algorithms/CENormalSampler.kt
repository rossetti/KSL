package ksl.simopt.solvers.algorithms

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import kotlin.isFinite
import kotlin.math.abs

class CENormalSampler(
    override val problemDefinition: ProblemDefinition,
    meanSmoother: Double = defaultMeanSmoother,
    sdSmoother: Double = defaultStdDevSmoother,
    coeffOfVariationThreshold: Double = defaultCoefficientOfVariationThreshold,
    streamNum: Int = 0,
    override val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : CESamplerIfc {

    override val dimension: Int = problemDefinition.inputSize

    init {
        require(meanSmoother > 0) { "Mean smoother must be greater than zero." }
        require(meanSmoother <= 1) { "Mean smoother must be less than or equal to one." }
        require(sdSmoother > 0) { "Standard deviation smoother must be greater than zero." }
        require(sdSmoother <= 1) { "Standard deviation smoother must be less than or equal to one." }
        require(coeffOfVariationThreshold > 0) { "Coefficient of variation threshold must be greater than zero." }
    }

    /**
     *  This can be used to increase/decrease the variability associated with the initial parameter setting.
     *  For example, a value of 1.1 increases the starting standard deviation by 10%. The setting
     *  must be a positive value.  The default value is specified by [defaultInitialVariabilityFactor].
     */
    var initialVariabilityFactor: Double = defaultInitialVariabilityFactor
        set(value) {
            require(value > 0) { "The default variance factor must be greater than zero." }
            field = value
        }

    /**
     *  Used to smooth the estimated values of the mean parameters via exponential smoothing.
     */
    var meanSmoother: Double = meanSmoother
        set(value) {
            require(value > 0) { "Mean smoother must be greater than zero." }
            require(value <= 1) { "Mean smoother must be less than or equal to one." }
            field = value
        }

    /**
     *  Used to smooth the estimated values of the standard deviation parameters via exponential smoothing.
     */
    var sdSmoother: Double = sdSmoother
        set(value) {
            require(value > 0) { "Standard deviation smoother must be greater than zero." }
            require(value <= 1) { "Standard deviation smoother must be less than or equal to one." }
            field = value
        }

    /**
     *  This threshold represents the bound used to consider whether the coefficient of variation
     *  for the population parameters has converged.
     */
    var cvThreshold: Double = coeffOfVariationThreshold
        set(value) {
            require(value > 0) { "Standard deviation threshold must be greater than zero." }
            field = value
        }

    private val myMeans: DoubleArray = DoubleArray(dimension) { 1.0 }

    /**
     *  A copy of the current mean parameters.
     */
    val means: DoubleArray
        get() = myMeans.copyOf()

    private val myStdDevs: DoubleArray = DoubleArray(dimension) { 1.0 }

    /**
     *  A copy of the current standard deviation parameters.
     */
    val stdDeviations: DoubleArray
        get() = myStdDevs.copyOf()

    private val myEliteStats = List(dimension) { Statistic() }

    /**
     *  A list of statistics for the population within the last sample of elites.
     */
    val eliteStatistics: List<StatisticIfc>
        get() = myEliteStats.toList()

    init {
        initializeParameters(problemDefinition.inputMidPoints)
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

    /**
     *  Returns a sample from the current cross-entropy distribution.
     */
    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "Array must have length equal to the dimension." }
        for (i in array.indices) {
            array[i] = rnStream.rNormal(myMeans[i], myStdDevs[i] * myStdDevs[i])
        }
    }

    /**
     *  This function sets the initial mean and standard deviation parameters for
     *  the sampler. The mean values are specified by the supplied array. The
     *  initial standard deviation values are set based on the range of the input values
     *  associated with the problem. If the range of possible values is higher, then
     *  the initial standard deviation is higher. The range is used to approximate
     *  the standard deviation. The [initialVariabilityFactor] can be used to inflate
     *  or deflate this setting as needed.
     *
     *  @param values the initial values to be assigned to the mean parameters. The
     *  size of the array must be equal to the dimension of the sampler.
     */
    override fun initializeParameters(values: DoubleArray) {
        require(values.size == dimension) { "The size of the parameters array must be equal to the dimension." }
        // first assign the means
        for (i in values.indices) {
            require(values[i].isFinite()) { "Mean vector must contain only finite values." }
            myMeans[i] = values[i]
            myEliteStats[i].reset()
        }
        // now assign the standard deviations
        val ranges = problemDefinition.inputRanges
        for (i in values.indices) {
            myStdDevs[i] = (ranges[i] / 4.0) * initialVariabilityFactor
            val stdDevThreshold = abs(myMeans[i]) * cvThreshold
            require(myStdDevs[i] > stdDevThreshold) {
                "The initial standard deviation (${myStdDevs[i]}) for parameter (${problemDefinition.inputNames[i]}) was set less than the stopping standard deviation threshold ($stdDevThreshold)." }
        }
    }

    override fun updateParameters(elites: List<DoubleArray>) {
        require(elites.isNotEmpty()) { "The elite sample must not be empty." }
        // estimate the mean and standard deviation for the elite samples for each dimension
        myEliteStats.forEach { it.reset() }
        for (array in elites) {
            require(array.size == dimension) { "The arrays within the elite sample must have length equal to the dimension." }
            for (i in array.indices) {
                myEliteStats[i].collect(array[i])
            }
        }
        // update the parameter vectors based on smoothing
        for (i in myMeans.indices) {
            myMeans[i] = meanSmoother * myMeans[i] + (1.0 - meanSmoother) * myEliteStats[i].average
            if (myEliteStats[i].count >= 2) {
                // Only update if there was enough data to estimate the standard deviation.
                myStdDevs[i] = sdSmoother * myStdDevs[i] + (1.0 - sdSmoother) * myEliteStats[i].standardDeviation
            }
        }
    }

    override fun parameters(): DoubleArray {
        return myMeans.copyOf()
    }

    /**
     *  The sampler is considered to be converged if the component standard deviations
     *  are less than their standard deviation thresholds based on the coefficient of
     *  variation threshold.
     */
    override fun hasConverged(): Boolean {
        for (i in myStdDevs.indices) {
            if (myStdDevs[i] > abs(myMeans[i]) * cvThreshold) return false
        }
        return true
    }

    /**
     *  Computes the standard deviation thresholds used to check if
     *  the distribution has converged. These values are abs(mean[i])*cvThreshold
     */
    val stdDeviationThresholds: DoubleArray
        get() = DoubleArray(dimension) { abs(myMeans[it]) * cvThreshold }

    override fun toString(): String {
        return buildString {
            appendLine("CENormalSampler")
            appendLine("streamNumber = $streamNumber")
            appendLine("dimension = $dimension")
            appendLine("variabilityFactor = $initialVariabilityFactor")
            appendLine("meanSmoother = $meanSmoother")
            appendLine("sdSmoother = $sdSmoother")
            appendLine("coefficient of variation threshold = $cvThreshold")
            appendLine("mean values = ${myMeans.contentToString()}")
            appendLine("standard deviations = ${myStdDevs.contentToString()}")
            appendLine("std deviation thresholds = ${stdDeviationThresholds.contentToString()}")
            append("Have standard deviation thresholds converge? = ${hasConverged()}")
        }
    }


    companion object {

        /**
         *  This can be used to globally increase/decrease the variability associated with the initial parameter setting.
         *  For example, a value of 1.1 increases the starting standard deviations by 10%. The setting
         *  must be a positive value.  The default value is specified as 1.0.
         */
        var defaultInitialVariabilityFactor: Double = 1.0
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

        /**
         *  The default value of the coefficient of variation threshold that is used to check
         *  if the distribution has converged. This value is used to compute standard
         *  deviation thresholds based on the current mean estimates. The default
         *  value is 0.03, which is considered a narrow or tight distribution.
         */
        var defaultCoefficientOfVariationThreshold: Double = 0.03
            set(value) {
                require(value > 0) { "Standard deviation threshold must be greater than zero." }
                field = value
            }
    }

}
