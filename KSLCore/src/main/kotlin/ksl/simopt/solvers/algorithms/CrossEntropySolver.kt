package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import kotlin.math.ceil

/**
 * Constructs an instance of CrossEntropySolver with specified parameters.
 *
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param ceSampler the cross-entropy sampler for the cross-entropy distribution
 * @param elitePct a value between 0 and 1 that represents the proportion of the CE sample
 * that determined the elite population. By default, this is 0.1, which can be controlled globally via the companion
 * object's [defaultElitePct].
 * @param ceSampleSize the sample size associated with the CE algorithm used to determine the elite solutions.
 * By default, this is 10 times the dimension of the problem. The factor, 10, can be globally controlled
 * via the companion object's [defaultCESampleSizeFactor]
 * @param noImproveThreshold Termination threshold on the largest number of iterations during which no
 * improvement of the best function value is found. By default, set to 5, which can be controlled
 * globally via the companion object's [defaultNoImproveThreshold]
 * @param maxIterations The maximum number of iterations allowed for the hill climbing process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param name Optional name identifier for this instance of StochasticHillClimber.
 */
class CrossEntropySolver(
    evaluator: EvaluatorIfc,
    val ceSampler: CESamplerIfc,
    elitePct: Double = defaultElitePct,
    ceSampleSize: Int = defaultCESampleSizeFactor * ceSampler.dimension,
    noImproveThreshold: Int = defaultNoImproveThreshold,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : StochasticSolver(
    evaluator, maxIterations,
    replicationsPerEvaluation, ceSampler.streamNumber,
    ceSampler.streamProvider, name
) {
    init {
        require(elitePct > 0) { "The elite percentage must be greater than 0" }
        require(elitePct < 1) { "The elite percentage must be less than 1" }
        require(ceSampleSize >= ceSampler.dimension) { "The CE sample size must be >= ${ceSampler.dimension}" }
        require(noImproveThreshold > 0) { "The default no improvement threshold must be greater than 0" }
    }

    /**
     * Constructs an instance of CrossEntropySolver with specified parameters.
     *
     * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
     * @param ceSampler the cross-entropy sampler for the cross-entropy distribution
     * @param elitePct a value between 0 and 1 that represents the proportion of the CE sample
     * that determined the elite population. By default, this is 0.1, which can be controlled globally via the companion
     * object's [defaultElitePct].
     * @param ceSampleSize the sample size associated with the CE algorithm used to determine the elite solutions.
     * By default, this is 10 times the dimension of the problem. The factor, 10, can be globally controlled
     * via the companion object's [defaultCESampleSizeFactor]
     * @param noImproveThreshold Termination threshold on the largest number of iterations during which no
     * improvement of the best function value is found. By default, set to 5, which can be controlled
     * globally via the companion object's [defaultNoImproveThreshold]
     * @param maxIterations The maximum number of iterations allowed for the hill climbing process.
     * @param replicationsPerEvaluation The number of replications to perform for each evaluation of a solution.
     * @param name Optional name identifier for this instance of StochasticHillClimber.
     */
    constructor(
        evaluator: EvaluatorIfc,
        ceSampler: CESamplerIfc,
        eliteProportion: Double = defaultElitePct,
        ceSampleSize: Int = defaultCESampleSizeFactor * ceSampler.dimension,
        noImproveThreshold: Int = defaultNoImproveThreshold,
        maxIterations: Int = defaultMaxNumberIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        name: String? = null
    ) : this(
        evaluator, ceSampler, eliteProportion, ceSampleSize, noImproveThreshold, maxIterations,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation), name
    )

    var elitePct: Double = elitePct
        set(value) {
            require(value > 0) { "The elite percentage must be greater than 0" }
            require(value < 1) { "The elite percentage must be less than 1" }
            field = value
        }

    var noImproveThreshold: Int = 5
        set(value) {
            require(value > 0) { "The no improvement threshold must be greater than 0" }
            field = value
        }

    var ceSampleSize: Int = ceSampleSize
        set(value) {
            require(value >= ceSampler.dimension) { "The CE sample size must be >= ${ceSampler.dimension}" }
            field = value
        }

    val eliteSize: Int
        get() {
            TODO("Not yet implemented")
            // this should depend on the size of the solution set, which might not
            // be the sample size due to "bad" solutions returned from the evaluator
            return ceil(elitePct * ceSampleSize).toInt()
        }

    override fun mainIteration() {
        TODO("Not yet implemented")
    }

    companion object {

        var defaultElitePct: Double = 0.1
            set(value) {
                require(value > 0) { "The default elite percentage must be greater than 0" }
                require(value < 1) { "The default elite percentage must be less than 1" }
                field = value
            }

        var defaultNoImproveThreshold: Int = 5
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }

        var defaultCESampleSizeFactor: Int = 10
            set(value) {
                require(value >= 1) { "The default CE sample size factor must be >= 1" }
                field = value
            }

    }

}