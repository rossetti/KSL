package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

private val logger = KotlinLogging.logger {}

/**
 * Adapter that exposes one or more `LoadedBundle`s through the existing
 * `ksl.simulation.ModelProviderIfc` contract used by `KSLAppSession`. This
 * lets callers that today take a `ModelProviderIfc` (such as the four
 * reference Swing apps) consume bundle-supplied models without any change
 * to the interaction-layer API.
 *
 * Model identifiers are flattened across all wrapped bundles: each
 * `KSLBundledModel.modelId` becomes a top-level identifier in this
 * provider. When two bundles ship a model with the same `modelId`, the
 * first wins and a warning is logged; the global key in the substrate is
 * the pair (bundleId, modelId), but this adapter exposes only the bare
 * modelId because that is what `ModelProviderIfc` callers know. Hosted
 * consumers that need to disambiguate (a future REST or MCP runtime)
 * should consume bundles directly rather than through this adapter.
 *
 * The iteration order of `modelIdentifiers` is deterministic: it follows
 * the constructor's bundle order, then within each bundle the declared
 * `models` order. Callers may rely on this for stable picker layouts.
 *
 * In addition to the `ModelProviderIfc` surface, this class exposes
 * `builderFor(modelIdentifier)`, returning the underlying
 * `ksl.simulation.ModelBuilderIfc`. This is needed by callers (for
 * example `ksl.controls.experiments.ParallelDesignedExperiment`) that
 * must construct many models from one builder rather than receive a
 * single built model. The base interface intentionally does not expose
 * a builder, so callers needing one must hold a typed reference to
 * `BundleModelProvider`.
 */
class BundleModelProvider(
    val bundles: List<LoadedBundle>
) : ModelProviderIfc {

    private val byModelId: Map<String, KSLBundledModel> = buildMap {
        for (loaded in bundles) {
            for (model in loaded.bundle.models) {
                val existing = putIfAbsent(model.modelId, model)
                if (existing != null) {
                    logger.warn {
                        "Duplicate modelId '${model.modelId}' from bundle " +
                                "'${loaded.bundle.bundleId}' shadowed by earlier registration; " +
                                "the first occurrence wins."
                    }
                }
            }
        }
    }

    /**
     * Lookup map keyed by the unambiguous (bundleId, modelId) pair.  Unlike
     * `byModelId`, this map never shadows: two bundles shipping the same
     * `modelId` get distinct entries here because their `bundleId`s differ.
     * Used by [provideModel] and [builderFor] overloads that take both
     * identifiers, which support the `ModelReference.ByBundleAndModelId`
     * resolution path.
     */
    private val byBundleAndModel: Map<Pair<String, String>, KSLBundledModel> = buildMap {
        for (loaded in bundles) {
            for (model in loaded.bundle.models) {
                put(loaded.bundle.bundleId to model.modelId, model)
            }
        }
    }

    override fun isModelProvided(modelIdentifier: String): Boolean =
        byModelId.containsKey(modelIdentifier)

    override fun provideModel(
        modelIdentifier: String,
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = byModelId[modelIdentifier]
            ?: throw IllegalArgumentException(
                "Unknown modelId '$modelIdentifier'. Available: ${modelIdentifiers()}"
            )
        return model.builder().build(modelConfiguration, experimentRunParameters)
    }

    override fun modelIdentifiers(): List<String> = byModelId.keys.toList()

    /**
     * Returns the `ModelBuilderIfc` for the model with the given identifier.
     * Distinct from `provideModel`: this returns the factory, not a built
     * model. Used by callers such as `ParallelDesignedExperiment` that
     * must build the same model many times.
     *
     * @throws IllegalArgumentException if `modelIdentifier` is not provided
     */
    fun builderFor(modelIdentifier: String): ModelBuilderIfc {
        val model = byModelId[modelIdentifier]
            ?: throw IllegalArgumentException(
                "Unknown modelId '$modelIdentifier'. Available: ${modelIdentifiers()}"
            )
        return model.builder()
    }

    /**
     * Returns true when a model is provided at the unambiguous
     * `(bundleId, modelId)` pair.  Distinct from the single-string
     * [isModelProvided], which uses the flat `byModelId` lookup with
     * first-wins shadowing.
     */
    fun isModelProvided(bundleId: String, modelId: String): Boolean =
        byBundleAndModel.containsKey(bundleId to modelId)

    /**
     * Builds and returns the model identified by the unambiguous
     * `(bundleId, modelId)` pair.  Used to resolve
     * `ModelReference.ByBundleAndModelId` against a multi-bundle
     * document.
     *
     * @throws IllegalArgumentException if no bundle in this provider
     * has [bundleId], or if the named bundle has no model with [modelId]
     */
    fun provideModel(
        bundleId: String,
        modelId: String,
        modelConfiguration: Map<String, String>? = null,
        experimentRunParameters: ExperimentRunParametersIfc? = null
    ): Model {
        val model = byBundleAndModel[bundleId to modelId]
            ?: throw IllegalArgumentException(
                "Unknown (bundleId, modelId) pair ('$bundleId', '$modelId'). " +
                        "Bundles: ${bundles.map { it.bundle.bundleId }}; " +
                        "available pairs: ${byBundleAndModel.keys}"
            )
        return model.builder().build(modelConfiguration, experimentRunParameters)
    }

    /**
     * Returns the `ModelBuilderIfc` for the model identified by the
     * unambiguous `(bundleId, modelId)` pair.  Distinct from
     * [provideModel]: this returns the factory, not a built model.
     *
     * @throws IllegalArgumentException if no bundle in this provider
     * has [bundleId], or if the named bundle has no model with [modelId]
     */
    fun builderFor(bundleId: String, modelId: String): ModelBuilderIfc {
        val model = byBundleAndModel[bundleId to modelId]
            ?: throw IllegalArgumentException(
                "Unknown (bundleId, modelId) pair ('$bundleId', '$modelId'). " +
                        "Bundles: ${bundles.map { it.bundle.bundleId }}; " +
                        "available pairs: ${byBundleAndModel.keys}"
            )
        return model.builder()
    }
}
