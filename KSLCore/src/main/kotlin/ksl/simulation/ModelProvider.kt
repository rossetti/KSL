package ksl.simulation

/**
 *  A function that promises to provide a model instance
 */
//typealias ModelProvider = () -> Model

/**
 *  An interface that promises to provide a model instance
 *  based on some identifier for the model.
 */
interface ModelProvider {

    fun isModelProvided(modelIdentifier: String) : Boolean

    fun provideModel(modelIdentifier: String) : Model

}