package ksl.simopt.solvers.algorithms.bo

import ksl.utilities.random.rng.RNStreamIfc
import kotlin.math.exp
import kotlin.math.ln

/**
 *  Strategy for selecting a Gaussian process's kernel hyperparameters (length scales and signal
 *  variance) from data. Implementations set the hyperparameters on the supplied model's kernel; the
 *  solver re-fits the model afterward.
 */
fun interface HyperparameterFitterIfc {

    /**
     *  Sets the kernel hyperparameters of [model] given the training data. Randomness (if any) is
     *  drawn through [rnStream].
     *
     *  @param model the Gaussian process whose kernel hyperparameters are to be set
     *  @param points the observed input points
     *  @param means the observed response means, aligned with [points]
     *  @param noiseVars the per-point noise variances, aligned with [points]
     *  @param rnStream the random number stream to use for any randomized search
     */
    fun fit(
        model: GaussianProcessModel,
        points: List<DoubleArray>,
        means: DoubleArray,
        noiseVars: DoubleArray,
        rnStream: RNStreamIfc
    )
}

/** The sample variance of [values], or 0.0 for fewer than two values. */
internal fun sampleVariance(values: DoubleArray): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    var ss = 0.0
    for (v in values) {
        val d = v - mean
        ss += d * d
    }
    return ss / (values.size - 1)
}

/**
 *  A heuristic, non-iterative hyperparameter setter: the ARD length scales are set to
 *  [lengthScaleFactor] times each input's range, and the signal variance is set to the sample
 *  variance of the observed means (or 1.0 if degenerate). This is fast, deterministic, and robust,
 *  making it a good default for a first-cut surrogate.
 *
 *  @param lengthScaleFactor the fraction of each input range used as that dimension's length scale.
 *  Must be > 0. Defaults to 1.0.
 */
class FixedHyperparameters(
    lengthScaleFactor: Double = 1.0
) : HyperparameterFitterIfc {

    var lengthScaleFactor: Double = lengthScaleFactor
        set(value) {
            require(value > 0.0) { "The length-scale factor must be > 0" }
            field = value
        }

    init {
        require(lengthScaleFactor > 0.0) { "The length-scale factor must be > 0" }
    }

    override fun fit(
        model: GaussianProcessModel,
        points: List<DoubleArray>,
        means: DoubleArray,
        noiseVars: DoubleArray,
        rnStream: RNStreamIfc
    ) {
        val ranges = model.problemDefinition.inputRanges
        model.kernel.lengthScales = DoubleArray(ranges.size) {
            val ls = lengthScaleFactor * ranges[it]
            if (ls > 0.0) ls else 1.0
        }
        val v = sampleVariance(means)
        model.kernel.signalVariance = if (v > 0.0) v else 1.0
    }

    override fun toString(): String = "FixedHyperparameters(lengthScaleFactor=$lengthScaleFactor)"
}

/**
 *  A maximum-likelihood hyperparameter fitter: it performs a random multi-start search over the
 *  log of the length scales and signal variance, refitting the GP at each candidate and keeping the
 *  hyperparameters with the highest log marginal likelihood. Length-scale candidates are drawn
 *  log-uniformly between [lengthScaleLowFactor] and [lengthScaleHighFactor] times each input range;
 *  signal-variance candidates are drawn log-uniformly around the data variance.
 *
 *  @param numStarts the number of random restarts. Must be >= 1. Defaults to [defaultNumStarts].
 *  @param lengthScaleLowFactor the lower bound factor on length scales. Must be > 0.
 *  @param lengthScaleHighFactor the upper bound factor on length scales. Must be > [lengthScaleLowFactor].
 */
class MleHyperparameterFitter(
    numStarts: Int = defaultNumStarts,
    lengthScaleLowFactor: Double = 0.05,
    lengthScaleHighFactor: Double = 2.0
) : HyperparameterFitterIfc {

    var numStarts: Int = numStarts
        set(value) {
            require(value >= 1) { "The number of starts must be >= 1" }
            field = value
        }

    var lengthScaleLowFactor: Double = lengthScaleLowFactor
    var lengthScaleHighFactor: Double = lengthScaleHighFactor

    init {
        require(numStarts >= 1) { "The number of starts must be >= 1" }
        require(lengthScaleLowFactor > 0.0) { "The low length-scale factor must be > 0" }
        require(lengthScaleHighFactor > lengthScaleLowFactor) { "The high factor must exceed the low factor" }
    }

    override fun fit(
        model: GaussianProcessModel,
        points: List<DoubleArray>,
        means: DoubleArray,
        noiseVars: DoubleArray,
        rnStream: RNStreamIfc
    ) {
        if (points.size < 2) {
            FixedHyperparameters().fit(model, points, means, noiseVars, rnStream)
            return
        }
        val ranges = model.problemDefinition.inputRanges
        val d = ranges.size
        val baseVar = sampleVariance(means).let { if (it > 0.0) it else 1.0 }
        var bestLogML = Double.NEGATIVE_INFINITY
        var bestLengthScales = DoubleArray(d) { if (ranges[it] > 0.0) ranges[it] else 1.0 }
        var bestSignalVariance = baseVar
        repeat(numStarts) {
            val lengthScales = DoubleArray(d) {
                val range = if (ranges[it] > 0.0) ranges[it] else 1.0
                logUniform(lengthScaleLowFactor * range, lengthScaleHighFactor * range, rnStream)
            }
            val signalVariance = logUniform(0.1 * baseVar, 10.0 * baseVar, rnStream)
            model.kernel.lengthScales = lengthScales
            model.kernel.signalVariance = signalVariance
            try {
                model.fit(points, means, noiseVars)
                val ll = model.logMarginalLikelihood()
                if (ll.isFinite() && ll > bestLogML) {
                    bestLogML = ll
                    bestLengthScales = lengthScales.copyOf()
                    bestSignalVariance = signalVariance
                }
            } catch (e: Exception) {
                // Skip hyperparameter candidates that produce a non-positive-definite covariance.
            }
        }
        model.kernel.lengthScales = bestLengthScales
        model.kernel.signalVariance = bestSignalVariance
    }

    private fun logUniform(low: Double, high: Double, rnStream: RNStreamIfc): Double {
        val lo = if (low > 0.0) low else 1e-12
        val hi = if (high > lo) high else lo * 10.0
        return exp(rnStream.rUniform(ln(lo), ln(hi)))
    }

    override fun toString(): String = "MleHyperparameterFitter(numStarts=$numStarts)"

    companion object {
        /** The default number of random restarts. By default, this is 10. */
        @JvmStatic
        var defaultNumStarts: Int = 10
            set(value) {
                require(value >= 1) { "The default number of starts must be >= 1" }
                field = value
            }
    }
}
