package ksl.simulation

import ksl.controls.experiments.ExperimentRunParameters

/**
 * A class implementing the ModelProviderIfc interface to manage and provide models
 * based on unique identifiers. It uses a mutable map to store model builders,
 * where each builder is responsible for instantiating a specific model.
 *
 * @constructor Creates a MapModelProvider with an optional initial set of model builders.
 * @param modelBuilders A mutable map containing model identifiers and their corresponding ModelCreator functions.
 * Default is an empty map.
 */
class MapModelProvider(
    private val modelBuilders: MutableMap<String, ModelBuilderIfc> = mutableMapOf()
) : ModelProviderIfc {

    /**
     *  Holds built models so that they do not have to be recreated
     *  to answer simple questions concerning their structure.
     */
    private val modelCache = mutableMapOf<String, Model>()

    /**
     * Secondary constructor for the MapModelProvider class.
     *
     * Initializes the provider with a single model builder mapped to the provided model identifier.
     *
     * @param modelIdentifier the identifier for the model
     * @param builder the builder responsible for creating the model
     */
    @Suppress("unused")
    constructor(modelIdentifier: String, builder: ModelBuilderIfc) : this(
        mutableMapOf(modelIdentifier to builder)
    )

    /**
     * Adds a ModelBuilderIfc to the provider.
     *
     * @param modelIdentifier the identifier for the model
     * @param builder the ModelBuilderIfc function that creates the model
     */
    @Suppress("unused")
    fun addModelCreator(modelIdentifier: String, builder: ModelBuilderIfc) {
        modelBuilders[modelIdentifier] = builder
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
        return modelBuilders.remove(modelIdentifier) != null
    }

    override fun isModelProvided(modelIdentifier: String): Boolean {
        return modelBuilders.containsKey(modelIdentifier)
    }

    override fun provideModel(modelIdentifier: String, jsonModelConfiguration: String?): Model {
         val model = modelBuilders[modelIdentifier]?.build(jsonModelConfiguration)
            ?: throw IllegalArgumentException("No model builder found for identifier: $modelIdentifier")
        if (!modelCache.containsKey(modelIdentifier)) modelCache[modelIdentifier] = model
        return model
    }

    override fun modelIdentifiers(): List<String> {
        return modelBuilders.keys.toList()
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