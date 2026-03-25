package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.SolutionCacheIfc

interface EvaluatorIfc {

    /**
     *  The total number of evaluations performed. An evaluation may have many replications.
     */
    val totalEvaluations: Int

    /**
     *  The total number of evaluations performed via the simulation oracle.
     */
    val totalOracleEvaluations: Int

    /**
     *  The total number of evaluations performed via the cache.
     */
    val totalCachedEvaluations: Int

    /**
     *  The total number of evaluation requests that were received.
     */
    val totalRequestsReceived: Int

    /**
     *  The total number of replications requested across all evaluation requests.
     */
    val totalReplications: Int
        get() = totalOracleReplications + totalCachedReplications

    /**
     *  The total number of replications performed by the simulation oracle.
     */
    val totalOracleReplications: Int

    /**
     *  The total number of replications satisfied by the cache.
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