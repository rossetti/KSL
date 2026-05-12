package ksl.app.bundle

import ksl.simulation.ModelBuilderIfc

/**
 * One model packaged inside a `KSLModelBundle`.
 *
 * A bundled model is a triple of (stable identity, human-facing labels,
 * a typed factory that knows how to build the underlying `ksl.simulation.Model`).
 * Capability declarations (`supportedApps`) let consumers filter their model
 * pickers without instantiating the model — they read from the bundle's static
 * metadata.
 *
 * Construction of a `Model` is deferred to `builder()`. Listing models in a
 * picker, computing capability badges, and reading the descriptor JSON from
 * an enriched JAR all happen without ever calling `builder().build(...)`.
 */
interface KSLBundledModel {

    /**
     * Stable identifier for this model within its enclosing bundle. Used as
     * a directory name inside the JAR (under `META-INF/ksl/models/`) and as
     * the cache filename for the lazy descriptor extractor; must therefore be
     * filesystem-safe (lowercase ASCII letters, digits, and hyphens by
     * convention; no slashes, no `..`).
     *
     * Two different bundles may legally reuse the same `modelId`. The global
     * key is the pair `(KSLModelBundle.bundleId, KSLBundledModel.modelId)`.
     */
    val modelId: String

    /** Free-form human-readable name shown in pickers. */
    val displayName: String

    /** One- or two-sentence summary suitable for a tooltip or side panel. */
    val description: String

    /**
     * The app kinds this model claims to support. The four Swing apps use this
     * to filter their pickers (e.g. the Experiment app shows only models whose
     * `supportedApps` contains `KSLAppKind.EXPERIMENT`). Today this is an
     * author honor system; a future `kslpkg validate` will cross-check the
     * claim against the model's extracted descriptor.
     */
    val supportedApps: Set<KSLAppKind>

    /**
     * Returns a typed factory for constructing the underlying `ksl.simulation.Model`.
     *
     * The returned `ModelBuilderIfc` already carries the engine's standard
     * build signature, accepting an optional configuration map and optional
     * `ExperimentRunParametersIfc`. The lazy descriptor extractor invokes
     * `builder().build(null, null).modelDescriptor()`; GUI consumers invoke
     * the builder with whatever inputs the user has authored.
     *
     * Implementations should be cheap to call but need not be idempotent —
     * each call may return a fresh builder.
     */
    fun builder(): ModelBuilderIfc
}
