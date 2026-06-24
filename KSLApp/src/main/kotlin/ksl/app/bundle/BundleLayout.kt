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
 * Per-model subdirectories live under `MODELS_ROOT/<modelId>/`.
 */
object BundleLayout {

    /**
     * Legacy `ServiceLoader` registration file. No longer read by the runtime
     * loader (bundles are manifest-driven); retained only for the bundle-meta
     * packaging that the book-examples JAR task still emits.
     */
    const val SERVICES_FILE: String =
        "META-INF/services/ksl.app.bundle.KSLModelBundle"

    /**
     * The bundle manifest. For a **manifest-driven (data-driven) bundle** this file
     * is *authoritative*: when present, `BundleLoader` decodes it (see
     * `ksl.app.config.BundleManifest` / `BundleManifestToml`) and constructs a
     * single reusable `ManifestBackedBundle` from it, so the JAR needs no compiled
     * `KSLModelBundle` class and no `META-INF/services` registration. This
     * manifest is now the only way a JAR declares a bundle.
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

    /**
     * Path of the author-curated catalog TOML for the given model. Optional;
     * written by `kslpkg enrich` (emitted from the model's DSL-derived catalog)
     * or hand-authored / written by the Bundle Workbench. When present it is the
     * authoritative catalog: the runtime loader overlays it onto the resolved
     * `ModelDescriptor`, replacing any `descriptor.catalog` baked into
     * `descriptor.json`. Absence simply leaves `descriptor.catalog` as-is.
     */
    fun catalogPath(modelId: String): String =
        "$MODELS_ROOT/$modelId/catalog.toml"
}
