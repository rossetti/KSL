package ksl.simopt.solvers.algorithms.bo

import ksl.simopt.problem.InputMap

/**
 *  Strategy for maximizing an acquisition function over the feasible region. This optimization uses
 *  the surrogate only — it makes no simulation oracle calls — so it can afford a large number of
 *  candidate evaluations.
 */
fun interface AcquisitionOptimizerIfc {
    /**
     *  Returns the input point that (approximately) maximizes [acq] over the feasible region.
     *
     *  @param acq the acquisition function to maximize
     *  @param surrogate the fitted surrogate to evaluate the acquisition against
     *  @param incumbent the current incumbent value passed to the acquisition function
     *  @param bo the solver requesting the optimization
     */
    fun optimize(
        acq: AcquisitionFunctionIfc,
        surrogate: SurrogateModelIfc,
        incumbent: Double,
        bo: BayesianOptimizationSolver
    ): InputMap
}

/**
 *  A robust, dependency-free acquisition optimizer: it scores a Latin-hypercube candidate set over
 *  the feasible region plus a set of local candidates around the current best, and returns the
 *  best-scoring candidate (rounded to granularity via the problem definition). Because acquisition
 *  evaluation is cheap (no simulation), a large candidate set gives good coverage.
 *
 *  @param numCandidates the number of global Latin-hypercube candidates. Must be >= 1. Defaults to
 *  [BayesianOptimizationSolver.defaultNumCandidates].
 *  @param numLocalRestarts the number of local candidates sampled around the current best. Must be
 *  >= 0. Defaults to [BayesianOptimizationSolver.defaultRestarts].
 *  @param localFraction the standard deviation of local perturbations, as a fraction of each input
 *  range. Must be > 0. Defaults to 0.1.
 */
class SampledAcquisitionOptimizer(
    numCandidates: Int = BayesianOptimizationSolver.defaultNumCandidates,
    numLocalRestarts: Int = BayesianOptimizationSolver.defaultRestarts,
    localFraction: Double = 0.1
) : AcquisitionOptimizerIfc {

    var numCandidates: Int = numCandidates
        set(value) {
            require(value >= 1) { "The number of candidates must be >= 1" }
            field = value
        }

    var numLocalRestarts: Int = numLocalRestarts
        set(value) {
            require(value >= 0) { "The number of local restarts must be >= 0" }
            field = value
        }

    var localFraction: Double = localFraction
        set(value) {
            require(value > 0.0) { "The local fraction must be > 0" }
            field = value
        }

    init {
        require(numCandidates >= 1) { "The number of candidates must be >= 1" }
        require(numLocalRestarts >= 0) { "The number of local restarts must be >= 0" }
        require(localFraction > 0.0) { "The local fraction must be > 0" }
    }

    override fun optimize(
        acq: AcquisitionFunctionIfc,
        surrogate: SurrogateModelIfc,
        incumbent: Double,
        bo: BayesianOptimizationSolver
    ): InputMap {
        val pd = bo.problemDefinition
        val candidates = ArrayList<DoubleArray>(numCandidates + numLocalRestarts)
        // Global coverage via Latin-hypercube sampling over the input ranges.
        bo.sampleLatinHyperCubePoints(numCandidates).forEach { candidates.add(it.inputValues) }
        // Local refinement around the current best solution.
        if (numLocalRestarts > 0) {
            val center = bo.bestSolution.inputMap.inputValues
            val ranges = pd.inputRanges
            repeat(numLocalRestarts) {
                val c = DoubleArray(center.size) { d ->
                    val sd = localFraction * ranges[d]
                    if (sd > 0.0) center[d] + bo.rnStream.rNormal(0.0, sd * sd) else center[d]
                }
                candidates.add(c)
            }
        }
        // Fall back to the current best if (somehow) no candidates were produced.
        if (candidates.isEmpty()) return bo.bestSolution.inputMap
        var bestPoint = candidates[0]
        var bestValue = Double.NEGATIVE_INFINITY
        for (x in candidates) {
            val value = acq.value(surrogate.predict(x), incumbent, bo)
            if (value > bestValue) {
                bestValue = value
                bestPoint = x
            }
        }
        return pd.toInputMap(bestPoint)
    }

    override fun toString(): String =
        "SampledAcquisitionOptimizer(numCandidates=$numCandidates, numLocalRestarts=$numLocalRestarts, localFraction=$localFraction)"
}
