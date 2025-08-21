package ksl.simopt.evaluator

/**
 *  An interface to define something that can simulate evaluation requests.
 */
interface SimulationOracleIfc {

    /**
     * Executes multiple simulations based on the provided evaluation request and maps each request
     * to a corresponding ResponseMap. Each request is processed individually, and the results of the
     * simulations are stored as a key-value pair in the returned map. If the input list is empty, an
     * exception is thrown.  This default implementation runs all the requests sequentially based
     * on the order within the evaluation request.
     *
     * @param evaluationRequest the request for simulation evaluations
     * @return a map where each key is a ModelInputs object and each value is a Result wrapping either
     *         a successful ResponseMap or an exception if the simulation fails for the corresponding evaluation.
     * @throws IllegalArgumentException if the input list of evaluations is empty.
     */
    @Suppress("unused")
    fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>>

}