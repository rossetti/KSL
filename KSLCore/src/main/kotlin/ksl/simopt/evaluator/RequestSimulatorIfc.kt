package ksl.simopt.evaluator

/**
 *  An interface to define something that can simulate requests.
 */
interface RequestSimulatorIfc {

    /**
     * Executes multiple simulations based on the provided list of request data and maps each request
     * to a corresponding ResponseMap. Each request is processed individually, and the results of the
     * simulations are stored as a key-value pair in the returned map. If the input list is empty, an
     * exception is thrown.  This default implementation runs all the requests sequentially based
     * on the order of the supplied list.
     *
     * @param requests the list of RequestData objects representing the simulation requests to be processed.
     *                 The list must contain at least one element.
     * @return a map where each key is a RequestData object and each value is a Result wrapping either
     *         a successful ResponseMap or an exception if the simulation fails for the corresponding request.
     * @throws IllegalArgumentException if the input list of requests is empty.
     */
    @Suppress("unused")
    fun simulateRequests(requests: List<ModelInputs>): Map<ModelInputs, Result<ResponseMap>>

}