package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.SolutionCacheIfc

interface EvaluatorIfc {

    /**
     *  A possible cache to hold evaluated solutions
     */
    val cache: SolutionCacheIfc?

    /**
     *  Processes the supplied requests for solutions. The solutions may come from an associated
     *  solution cache (if present) or via evaluations by the simulation oracle.  The list of
     *  requests may have duplicated inputs, in which case, the solution will also be a duplicate.
     *  That is, no extra evaluations occur for duplicates in the list of requests. Any new
     *  solutions that result due to the processing will be entered into the cache (according
     *  to the rules governing the cache).  Any incoming requests that have input range
     *  infeasible input settings will not be evaluated and will result in solutions that
     *  are bad and infeasible.
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