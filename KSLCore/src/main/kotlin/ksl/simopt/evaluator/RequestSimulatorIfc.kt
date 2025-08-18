package ksl.simopt.evaluator

interface RequestSimulatorIfc {

    /**
     *  Control whether the evaluator switches to the use of common random numbers (CRN).
     *  When the evaluator starts using CRN, it will continue to use CRN until told
     *  not to use CRN.
     *
     * @param modelIdentifier the model identifier of the model for application of CRN
     *  @param crnOption if true, the evaluator should start using common random numbers
     *  when evaluating requests.
     */
    fun useCommonRandomNumbers(modelIdentifier: String, crnOption: Boolean)

    /**
     * Returns true if the CRN option is on for the identified model.
     * @param modelIdentifier the model identifier of the model for application of CRN
     */
    fun crnOption(modelIdentifier: String) : Boolean

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
    fun simulateRequests(requests: List<RequestData>): Map<RequestData, Result<ResponseMap>>

}