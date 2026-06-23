package ksl.app.bundle

/**
 * Identifies the configuration shape of a `KSLConfigRecipe` shipped inside a bundle.
 *
 * The kind tells a consumer which deserializer to apply to the recipe's stream
 * (for example, `RunConfigurationToml` for `RUN` and `SCENARIO_BATCH`,
 * `OptimizationRunConfigurationToml` for `OPTIMIZATION`). This keeps the bundle
 * SPI itself free of any dependency on a particular config format — the format
 * lives in `ksl.app.config.*`, the SPI only labels the file.
 *
 * The kinds correspond one-to-one with the four sealed variants of `RunSpec`
 * and with the per-kind subdirectories under `META-INF/ksl/models/<modelId>/`
 * defined in `BundleLayout`.
 */
enum class ConfigRecipeKind {
    /** A single `RunConfiguration`. */
    RUN,

    /** A batch of scenarios authored together. */
    SCENARIO_BATCH,

    /** A designed-experiment specification over one model's controls. */
    EXPERIMENT,

    /** An `OptimizationRunConfiguration`. */
    OPTIMIZATION
}
