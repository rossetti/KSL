package ksl.simulation

/**
 * A type alias for a function that creates and returns an instance of the `Model` class.
 */
@Suppress("unused")
typealias ModelCreator = () -> Model

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
    fun isModelProvided(modelIdentifier: String) : Boolean

    /**
     * Provides a model instance based on the given identifier.
     * If the model corresponding to the identifier is available,
     * this method returns the instance of the model.
     *
     * @param modelIdentifier the identifier of the model to retrieve
     * @return the model instance corresponding to the provided identifier
     */
    fun provideModel(modelIdentifier: String) : Model

    /**
     * Retrieves a list of model identifiers that are available.
     *
     * @return a list of strings, each representing an identifier for a model.
     */
    @Suppress("unused")
    fun modelIdentifiers() : List<String>

    /**
     * Retrieves a list of response names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose response names are to be retrieved
     * @return a list of response names corresponding to the specified model
     */
    @Suppress("unused")
    fun responseNames(modelIdentifier: String) : List<String> {
        return provideModel(modelIdentifier).responseNames
    }
}