package ksl.simulation

import ksl.controls.experiments.ExperimentRunParameters

/**
 * A class implementing the ModelProviderIfc interface to manage and provide models
 * based on unique identifiers. It uses a mutable map to store model creators,
 * where each creator is responsible for instantiating a specific model.
 *
 * @constructor Creates a MapModelProvider with an optional initial set of model creators.
 * @param modelCreators A mutable map containing model identifiers and their corresponding ModelCreator functions. Default is an empty map.
 */
class MapModelProvider(
    private val modelCreators: MutableMap<String, ModelCreator> = mutableMapOf()
) : ModelProviderIfc {

    private val modelCache = mutableMapOf<String, Model>()

    constructor(modelIdentifier: String, creator: ModelCreator) : this(
        mutableMapOf(modelIdentifier to creator)
    )

    /**
     * Adds a ModelCreator to the provider.
     *
     * @param modelIdentifier the identifier for the model
     * @param creator the ModelCreator function that creates the model
     */
    @Suppress("unused")
    fun addModelCreator(modelIdentifier: String, creator: ModelCreator) {
        modelCreators[modelIdentifier] = creator
    }

    /**
     * Removes a ModelCreator from the provider.
     *
     * @param modelIdentifier the identifier of the model to remove
     * @return true if the model was removed, false if it wasn't found
     */
    @Suppress("unused")
    fun removeModelCreator(modelIdentifier: String): Boolean {
        modelCache.remove(modelIdentifier)
        return modelCreators.remove(modelIdentifier) != null
    }

    override fun isModelProvided(modelIdentifier: String): Boolean {
        return modelCreators.containsKey(modelIdentifier)
    }

    override fun provideModel(modelIdentifier: String): Model {
         val model = modelCreators[modelIdentifier]?.invoke()
            ?: throw IllegalArgumentException("No model creator found for identifier: $modelIdentifier")
        if (!modelCache.containsKey(modelIdentifier)) modelCache[modelIdentifier] = model
        return model
    }

    override fun modelIdentifiers(): List<String> {
        return modelCreators.keys.toList()
    }

    override fun responseNames(modelIdentifier: String): List<String> {
        if (modelCache.containsKey(modelIdentifier)){
            return modelCache[modelIdentifier]!!.responseNames
        }
        return super.responseNames(modelIdentifier)
    }

    override fun experimentalParameters(modelIdentifier: String): ExperimentRunParameters {
        if (modelCache.containsKey(modelIdentifier)){
            return modelCache[modelIdentifier]!!.extractRunParameters()
        }
        return super.experimentalParameters(modelIdentifier)
    }

    override fun inputNames(modelIdentifier: String): List<String> {
        if (modelCache.containsKey(modelIdentifier)){
            return modelCache[modelIdentifier]!!.inputKeys()
        }
        return super.inputNames(modelIdentifier)
    }
}