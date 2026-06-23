package ksl.app.bundle

/**
 * The service-provider interface for a self-describing KSL model bundle.
 *
 * A bundle is a JAR containing one or more `KSLBundledModel` instances plus
 * the metadata needed to discover, identify, and version them. Bundles are
 * discovered at runtime via `java.util.ServiceLoader` against this interface;
 * the corresponding registration lives at
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` inside the JAR.
 *
 * This interface is GUI-agnostic by design. The four Swing apps are the
 * first consumer, but the same surface is intended to be consumed by a
 * future REST/gRPC host, an MCP server for agent-callable simulation, and
 * a CLI scripting host. Bundle metadata therefore never carries
 * presentation hints (icons, themes, layout); such concerns live in
 * per-consumer sidecars.
 *
 * Optional metadata (`author`, `homepage`, `license`, `tags`, `recipesFor`)
 * has defaulted implementations so the SPI may grow in a backward-compatible
 * fashion: new optional members are added with safe defaults, allowing
 * existing bundle implementations to compile unchanged.
 */
interface KSLModelBundle {

    /**
     * Globally unique stable identifier for this bundle. Authors are
     * encouraged to use a reverse-DNS-flavoured string
     * (e.g. `edu.uark.examples.queueing-101`). Used as the persistent
     * key in recent-bundles lists, the on-disk cache layout, and any
     * future catalog index.
     */
    val bundleId: String

    /** Human-readable name shown in bundle pickers. */
    val displayName: String

    /** Short description, typically two or three sentences. */
    val description: String

    /**
     * Bundle-author's own version of this bundle's content. Independent of
     * `kslApiVersion`. Semver is encouraged but not enforced.
     */
    val version: String

    /**
     * The major.minor version of the KSL API the bundle was built against.
     * Loaders may refuse to load (or warn on) a bundle whose `kslApiVersion`
     * is incompatible with the running KSL build. This is the bundle-level
     * compatibility surface; the descriptor JSON carries its own schema
     * version independently.
     */
    val kslApiVersion: String

    /**
     * The models packaged in this bundle. Building this list must not
     * instantiate `ksl.simulation.Model` objects — that work is deferred
     * to each `KSLBundledModel.builder()`.
     */
    val models: List<KSLBundledModel>

    /** Optional. Bundle author name or organisation. */
    val author: String?
        get() = null

    /** Optional. URL pointing to the bundle's project page or documentation. */
    val homepage: String?
        get() = null

    /** Optional. License identifier (e.g. an SPDX identifier such as "MIT"). */
    val license: String?
        get() = null

    /** Optional. Free-form tags for cataloging and search. */
    val tags: Set<String>
        get() = emptySet()

    /**
     * Returns the author-curated `KSLConfigRecipe` instances for the given
     * model. Default: empty list.
     *
     * Implementations typically enumerate JAR resources under the per-kind
     * subdirectories defined in `BundleLayout` and wrap each as a
     * `KSLConfigRecipe`.
     *
     * @param modelId the `KSLBundledModel.modelId` whose recipes are requested
     * @return recipes belonging to that model; empty if the bundle ships none
     */
    fun recipesFor(modelId: String): List<KSLConfigRecipe> = emptyList()
}
