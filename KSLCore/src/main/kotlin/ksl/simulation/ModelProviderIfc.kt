package ksl.simulation

import ksl.controls.experiments.ExperimentRunParameters

/**
 * A type alias for a function that creates and returns an instance of the `Model` class.
 */
@Suppress("unused")
typealias ModelCreator = (jsonModelConfig: String) -> Model

/**
 * An interface representing a builder for creating Model instances.
 *
 * This interface defines an abstract method that is responsible for
 * constructing a Model object.
 * It can be implemented to encapsulate the logic required to create
 * specific types of model instances.
 *
 * ## Build-call contract
 *
 * Implementations of [build] **must be pure constructors**: produce
 * a fresh, usable [Model] from the supplied inputs and do nothing
 * else.  The framework reserves the right to call [build] more than
 * once per analyst-visible "Run" (e.g. the validator and the
 * orchestrator each independently build the model for their own
 * purposes, and a probe-time build may happen for GUI bootstrap),
 * so implementations **must not**:
 *
 *  - Perform expensive or slow I/O — disk reads beyond what's needed
 *    to construct the model, network calls, external process spawns,
 *    etc.  A slow `build` directly slows every Run.
 *  - Mutate external state — write to files, increment counters,
 *    open database connections, register JVM-wide singletons.
 *    External effects fire once per build, not once per Run.
 *  - Hold expensive constructor-time resources beyond the returned
 *    Model's lifetime.  A built Model that's never simulated (e.g.
 *    discarded by a validator after shape inspection) should leave
 *    no lingering OS resources behind.
 *  - Assume a single `build` per Run.  Caching of immutable
 *    configuration objects across multiple builds is fine; mutable
 *    state is not.
 *
 * Implementations **may** assume:
 *
 *  - The same `(modelConfiguration, experimentRunParameters)` pair
 *    yields equivalent (not necessarily identical) Models on
 *    successive calls.
 *  - The returned Model will be configured further by the caller
 *    (run-parameter overrides, control overrides, RV overrides,
 *    `model.outputDirectory = ...`) before `simulate()` is invoked
 *    on it.  Builders should leave room for those mutations rather
 *    than locking down state in the constructor.
 */
interface ModelBuilderIfc {

    /**
     * Constructs a `Model` instance based on the provided configuration strings.
     *
     * See the interface KDoc for the build-call contract — in
     * particular, that this method **may be invoked more than once
     * per Run** and must therefore be a side-effect-free pure
     * constructor.
     *
     * @param modelConfiguration A map of strings representing the model configuration. The key string
     * should contain the necessary information for being able to use the paired string value.
     * The stored string values could be anything. For example, the value could be a JSON
     * string and the key provides information about how to process the JSON.
     * The intent is that the map should be sufficient to build an appropriate `Model` instance.
     * The map is optional. The function should return a model that is usable.
     * @param experimentRunParameters the run parameters to apply to the model during the building process
     * @return A `Model` object constructed using the provided configuration information.
     */
    fun build(
        modelConfiguration: Map<String, String>? = null,
        experimentRunParameters: ExperimentRunParametersIfc? = null
    ): Model

}

/**
 *  An interface that promises to provide a model instance
 *  based on some identifier for the model.
 */
interface ModelProviderIfc {

    /**
     * Verifies whether a model is available based on the given identifier.
     *
     * @param modelIdentifier the identifier of the model to check availability for
     * @return true if the model corresponding to the given identifier is available, false otherwise
     */
    fun isModelProvided(modelIdentifier: String): Boolean

    /**
     * Provides a model instance based on the given identifier.
     * If the model corresponding to the identifier is available,
     * this method returns the instance of the model.
     *
     * @param modelIdentifier the identifier of the model to retrieve
     * @param modelConfiguration A map of strings representing the model configuration. The key string
     * should contain the necessary information for being able to use the paired string value.
     * The stored string values could be anything. For example, the value could be a JSON
     * string and the key provides information about how to process the JSON.
     * The intent is that the map should be sufficient to build an appropriate `Model` instance.
     * The map is optional. The function should return a model that is usable.
     * @return the model instance corresponding to the provided identifier
     */
    fun provideModel(
        modelIdentifier: String,
        modelConfiguration: Map<String, String>? = null,
        experimentRunParameters: ExperimentRunParametersIfc? = null
    ): Model

    /**
     * Retrieves a list of model identifiers that are available.
     *
     * @return a list of strings, each representing an identifier for a model.
     */
    @Suppress("unused")
    fun modelIdentifiers(): List<String>

    /**
     * Retrieves a list of response names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose response names are to be retrieved
     * @return a list of response names corresponding to the specified model
     */
    @Suppress("unused")
    fun responseNames(modelIdentifier: String): List<String> {
        return provideModel(modelIdentifier).responseNames
    }

    /**
     * Retrieves the list of input names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose input names are to be retrieved
     * @return a list of strings representing the input names corresponding to the specified model
     */
    @Suppress("unused")
    fun inputNames(modelIdentifier: String): List<String> {
        return provideModel(modelIdentifier).inputKeys()
    }

    /**
     * Retrieves the experimental run parameters for the model identified by the given identifier.
     * This method extracts detailed configurations and settings required to execute the experiment.
     *
     * @param modelIdentifier the identifier of the model whose experimental parameters are to be retrieved
     * @return an instance of [ExperimentRunParameters] containing the run parameters for the specified model
     */
    @Suppress("unused")
    fun experimentalParameters(modelIdentifier: String): ExperimentRunParameters {
        return provideModel(modelIdentifier).extractRunParameters()
    }
}