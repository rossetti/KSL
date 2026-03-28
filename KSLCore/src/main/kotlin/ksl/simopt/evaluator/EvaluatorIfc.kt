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