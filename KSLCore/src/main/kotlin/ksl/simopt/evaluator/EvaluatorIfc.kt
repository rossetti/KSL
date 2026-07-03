package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.SolutionCacheIfc

interface EvaluatorIfc {

    /**
     * The total number of times the evaluate() method has been invoked.
     * This essentially tracks the number of "batches" or "generations" processed.
     */
    val totalEvaluatorCalls: Int

    /**
     * The total number of unique design points (ModelInputs) requested for evaluation
     * across all evaluator calls.
     */
    val totalDesignPointsEvaluated: Int

    /**
     *  The total number of replications requested across all evaluation requests.
     */
    val totalReplicationsRequested: Int
        get() = totalOracleReplications + totalCachedReplications

    /**
     * The total number of replications actually executed by the simulation oracle.
     */
    val totalOracleReplications: Int

    /**
     * The total number of replications successfully bypassed/satisfied by the cache.
     */
    val totalCachedReplications: Int

    /**
     *  A possible cache to hold evaluated solutions
     */
    val cache: SolutionCacheIfc?

    /**
     * Resets the evaluation clock: the per-call counter stamped into produced solutions
     * as their evaluation number, which drives dynamic penalty-function ramps (the
     * iteration count in Park-and-Kim-style penalties). Restart-style solvers reset the
     * clock between independent runs so each run's penalty ramp begins fresh instead of
     * inheriting ever-steeper multipliers from earlier runs. Resetting the clock does
     * NOT disturb the cumulative statistics counters (totalEvaluatorCalls and friends),
     * which feed post-run evaluator metrics. The default implementation does nothing,
     * for evaluators without a clock.
     *
     * Note that the clock stamps newly evaluated (or merged) solutions only: a solution
     * served entirely from a cache is an immutable record of its original evaluation and
     * retains the evaluation number it was first stamped with.
     */
    fun resetEvaluationClock() {
    }

    /**
     *  Processes the supplied requests for solutions. The solutions may come from an associated
     *  solution cache (if present or allowed) or via evaluations by the simulation oracle.
     *  The CRN option is applied to the set of requests and does not permit
     *  cached solutions, even if caching is permitted.
     *
     *  @param evaluationRequest a request for evaluation
     *  @return a map containing the model inputs and resulting solutions as pairs
     */
    fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution>

    companion object {

        @JvmStatic
        val logger: KLogger = KotlinLogging.logger {}

    }
}