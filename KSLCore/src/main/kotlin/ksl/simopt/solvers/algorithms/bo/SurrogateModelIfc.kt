package ksl.simopt.solvers.algorithms.bo

import kotlin.math.sqrt

/**
 *  A surrogate model that is fit to noisy observations and predicts a posterior mean and variance
 *  at an arbitrary input point. Bayesian optimization optimizes a cheap acquisition function over
 *  this surrogate rather than over the expensive simulation.
 */
interface SurrogateModelIfc {

    /**
     *  Fits the surrogate to the supplied observations.
     *
     *  @param points the observed input points (each a coordinate array in problem input order)
     *  @param means the observed (noisy) response means, aligned with [points]
     *  @param noiseVars the per-point variance of the observed mean (e.g. sample variance / count),
     *  aligned with [points]
     */
    fun fit(points: List<DoubleArray>, means: DoubleArray, noiseVars: DoubleArray)

    /**
     *  Predicts the posterior mean and variance at [x]. The model must have been fit first.
     */
    fun predict(x: DoubleArray): Prediction

    /**
     *  A posterior prediction: the [mean] and (non-negative) [variance], with a convenience
     *  [standardDeviation].
     */
    data class Prediction(val mean: Double, val variance: Double) {
        val standardDeviation: Double
            get() = sqrt(if (variance > 0.0) variance else 0.0)
    }
}
