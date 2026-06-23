package ksl.app.bundle

/**
 * The well-known in-JAR paths that make up a KSL bundle. This object is the
 * authoritative spec for the bundle JAR layout; the `kslpkg` CLI tool, the
 * runtime loader, and any tests must reference these constants rather than
 * hard-coding paths in multiple places.
 *
 * The layout is a public contract. New entries may be added freely; existing
 * entries are not renamed or removed without a major-version bump of the
 * bundle SPI (see Phase 6 plan §4.8).
 *
 * Per-model subdirectories live under `MODELS_ROOT/<modelId>/` and follow a
 * one-directory-per-recipe-kind convention so a bundle author can drop new
 * recipe files into a directory without editing code.
 */
object BundleLayout {

    /**
     * The `ServiceLoader` registration file. The JAR must contain this entry
     * for the runtime loader to discover `KSLModelBundle` implementations.
     */
    const val SERVICES_FILE: String =
        "META-INF/services/ksl.app.bundle.KSLModelBundle"

    /**
     * Optional declarative bundle identity file. When present, tooling may
     * read identity fields (`bundleId`, `version`, etc.) from this TOML
     * without classloading. The Kotlin `KSLModelBundle` implementation
     * remains the authoritative source at runtime.
     */
    const val BUNDLE_TOML: String =
        "META-INF/ksl/bundle.toml"

    /** Root directory under which per-model resources are organised. */
    const val MODELS_ROOT: String =
        "META-INF/ksl/models"

    /**
     * Path of the serialised `ModelDescriptor` JSON for the given model.
     * Written by `kslpkg enrich`; read by the runtime loader; absence
     * triggers the lazy-extraction fallback with on-disk caching.
     */
    fun descriptorPath(modelId: String): String =
        "$MODELS_ROOT/$modelId/descriptor.json"

    /** Directory holding `ConfigRecipeKind.RUN` recipes for the given model. */
    fun runRecipesDir(modelId: String): String =
        "$MODELS_ROOT/$modelId/run"

    /** Directory holding `ConfigRecipeKind.SCENARIO_BATCH` recipes for the given model. */
    fun scenarioRecipesDir(modelId: String): String =
        "$MODELS_ROOT/$modelId/scenarios"

    /** Directory holding `ConfigRecipeKind.OPTIMIZATION` recipes for the given model. */
    fun optimizationRecipesDir(modelId: String): String =
        "$MODELS_ROOT/$modelId/optimization"

    /**
     * Directory holding `ConfigRecipeKind.EXPERIMENT` recipes for the given model.
     * Reserved now; designed-experiment recipe authoring is wired up in a
     * later Phase 6 sub-phase.
     */
    fun experimentRecipesDir(modelId: String): String =
        "$MODELS_ROOT/$modelId/experiment"
}
