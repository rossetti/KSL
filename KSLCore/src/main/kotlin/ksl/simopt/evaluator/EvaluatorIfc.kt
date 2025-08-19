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
     *  @return a list containing a solution for each request
     */
    fun evaluate(evaluationRequest: EvaluationRequest): List<Solution>

//    /**
//     *  Processes the supplied requests for solutions. The solutions may come from an associated
//     *  solution cache (if present) or via evaluations by the simulation oracle.  The list of
//     *  requests may have duplicated inputs, in which case, the solution will also be a duplicate.
//     *  That is, no extra evaluations occur for duplicates in the list of requests. Any new
//     *  solutions that result due to the processing will be entered into the cache (according
//     *  to the rules governing the cache).  Any incoming requests that have input range
//     *  infeasible input settings will not be evaluated and will result in solutions that
//     *  are bad and infeasible.
//     *
//     *  @param rawRequests a list of evaluation requests
//     *  @return a list containing a solution for each request
//     */
//    fun evaluate(rawRequests: List<ModelInputs>): List<Solution>

//    /**
//     *  Processes the supplied model inputs for a solution. The solution may come from an associated
//     *  solution cache (if present) or via an evaluation by the simulation oracle.
//     *  A solution that results due to the processing will be entered into the cache (according
//     *  to the rules governing the cache).  If the model inputs are input range-infeasible,
//     *  then a bad and infeasible solution will be returned.
//     *
//     *  @param modelInputs a request needing evaluation
//     *  @return the solution associated with the request
//     */
//    fun evaluate(modelInputs: ModelInputs): Solution {
//        return evaluate(listOf(modelInputs)).first()
//    }

    companion object {

        @JvmStatic
        val logger: KLogger = KotlinLogging.logger {}

    }
}