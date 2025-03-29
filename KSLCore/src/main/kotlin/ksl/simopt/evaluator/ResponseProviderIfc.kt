package ksl.simopt.evaluator

/**
 *  A functional interface that promises to convert a list of requests for evaluation
 *  into responses associated with the requests. Used within [Evaluator] to
 *  communicated with the simulation oracle.
 */
fun interface ResponseProviderIfc {

    /**
     * Promises to convert evaluation requests into responses.
     *
     * @param requests - a list of requests to evaluate
     * @return - a map containing responses for each request evaluated
     */
    fun provideResponses(
        requests: List<EvaluationRequest>
    ): Map<EvaluationRequest, ResponseMap>

}