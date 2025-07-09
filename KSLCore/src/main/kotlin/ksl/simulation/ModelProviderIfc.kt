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
 * constructing a Model object based on the provided model identifier.
 * It can be implemented to encapsulate the logic required to create
 * specific types of model instances.
 */
fun interface ModelBuilderIfc {

    /**
     * Constructs a `Model` instance based on the provided JSON configuration string.
     *
     * @param jsonModelConfig A JSON string representing the model configuration. This string
     * should contain the necessary data and parameters required for building the `Model` instance.
     * @return A `Model` object constructed using the provided JSON configuration.
     */
    fun build(jsonModelConfig: String?): Model

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
     * @param jsonModelConfiguration an optional JSON string that can be used during the model building process
     * @return the model instance corresponding to the provided identifier
     */
    fun provideModel(modelIdentifier: String, jsonModelConfiguration: String? = null): Model

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
    fun experimentalParameters(modelIdentifier: String) : ExperimentRunParameters{
        return provideModel(modelIdentifier).extractRunParameters()
    }
}