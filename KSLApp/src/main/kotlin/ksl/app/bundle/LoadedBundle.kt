package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import ksl.app.config.CatalogValidation
import ksl.app.config.ModelCatalogToml
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Runtime-managed wrapper around one discovered `KSLModelBundle`. Holds the
 * bundle's classloader and (when applicable) its source JAR and content hash,
 * and provides on-demand access to each model's `ksl.simulation.ModelDescriptor`.
 *
 * A loadable bundle must be **complete**: `descriptorFor` resolves each model's
 * descriptor from the in-JAR resource at `BundleLayout.descriptorPath(modelId)`
 * (baked in by `kslpkg assemble` / the Bundle Workbench). If a declared model has
 * no embedded descriptor the bundle is incomplete and `descriptorFor` throws
 * `IncompleteBundleException`; [missingDescriptors] reports such gaps up front so
 * a consumer can reject the bundle at load rather than failing mid-run. Results
 * are memoized in-memory for the lifetime of this `LoadedBundle`.
 *
 * `LoadedBundle` is `AutoCloseable`. The `ownedResources` parameter is the
 * `AutoCloseable` (if any) whose lifetime this instance manages — typically
 * the `URLClassLoader` created for a JAR. For classpath-loaded bundles
 * `ownedResources` is `null` and `close` is a no-op.
 *
 * When several `LoadedBundle`s come from the same `BundleLoader.loadJar` call
 * (one JAR declaring multiple bundles), they share a classloader; close them
 * as a group, since closing any one of them releases resources that the
 * others depend on.
 */
class LoadedBundle internal constructor(
    val bundle: KSLModelBundle,
    val sourceJar: Path?,
    private val classLoader: ClassLoader,
    private val ownedResources: AutoCloseable?,
    private val jarSha256: String?,
    /**
     *  When this bundle was built/packaged: the JAR manifest's `Build-Time`
     *  attribute if present, else the JAR file's last-modified time; `null`
     *  for classpath-loaded bundles.  Used to resolve same-`(bundleId, version)`
     *  duplicates **newest-wins**, so a rebuilt-but-not-reversioned bundle's
     *  most recent copy is the one that stays loaded.
     */
    val builtAt: java.time.Instant? = null
) : AutoCloseable {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private val myDescriptors = ConcurrentHashMap<String, ModelDescriptor>()

    @Volatile
    private var closed: Boolean = false

    /**
     * SHA-256 of the source JAR's bytes, or `null` for classpath-loaded
     * bundles (which have no backing JAR file).  Lets a caller detect that a
     * JAR at a previously-loaded path has been rebuilt — same `sourceJar`,
     * different content — which is the signal a bundle library uses to decide
     * a reload is warranted rather than a no-op.
     */
    val contentHash: String?
        get() = jarSha256

    /**
     * Returns the `ModelDescriptor` for the given `modelId`, read from the bundle's
     * embedded in-JAR descriptor (with any `catalog.toml` overlay applied) and
     * memoized. A complete bundle always embeds its descriptors; if this one does
     * not, the bundle is incomplete and this throws `IncompleteBundleException`.
     *
     * @throws IllegalArgumentException if `modelId` is not declared by this bundle
     * @throws IllegalStateException if this `LoadedBundle` has already been closed
     * @throws IncompleteBundleException if the bundle declares `modelId` but embeds
     *   no descriptor for it (the JAR was not fully assembled)
     */
    fun descriptorFor(modelId: String): ModelDescriptor {
        check(!closed) { "LoadedBundle ${bundle.bundleId} has been closed" }
        myDescriptors[modelId]?.let { return it }

        if (bundle.models.none { it.modelId == modelId }) {
            throw IllegalArgumentException(
                "Bundle ${bundle.bundleId} does not declare model '$modelId'. " +
                        "Available: ${bundle.models.map { it.modelId }}"
            )
        }

        // A complete bundle embeds each model's descriptor. If it is absent (or
        // unreadable), the JAR is not a fully-assembled bundle — reject and point
        // the user to (re)assembly rather than silently rebuilding the model.
        val base = readInJarDescriptor(modelId)
            ?: throw IncompleteBundleException(bundle.bundleId, modelId)
        return finalize(modelId, base)
    }

    /**
     * The ids of declared models that have **no** embedded in-JAR descriptor — i.e.
     * the bundle is incomplete (not fully assembled). Empty for a complete bundle.
     * Lets a consumer reject an incomplete bundle at load instead of failing when a
     * model is first run.
     *
     * @throws IllegalStateException if this `LoadedBundle` has already been closed
     */
    fun missingDescriptors(): List<String> {
        check(!closed) { "LoadedBundle ${bundle.bundleId} has been closed" }
        return bundle.models.map { it.modelId }.filter { !hasInJarDescriptor(it) }
    }

    private fun hasInJarDescriptor(modelId: String): Boolean =
        classLoader.getResource(BundleLayout.descriptorPath(modelId)) != null

    /**
     * Applies the in-JAR `catalog.toml` overlay (if any) to the resolved [base]
     * descriptor, then memoizes the result. The catalog overlay is a separate
     * in-JAR resource applied fresh on resolution, so the memoized value carries
     * the authoritative catalog.
     */
    private fun finalize(modelId: String, base: ModelDescriptor): ModelDescriptor =
        memoize(modelId, applyCatalogOverlay(modelId, base))

    /**
     * If a `catalog.toml` is present at [BundleLayout.catalogPath], decodes it,
     * drops entries that no longer resolve against [base] (logging a warning),
     * re-derives input kinds, and returns `base.copy(catalog = …)`. When no
     * `catalog.toml` is present, [base] is returned unchanged so any
     * `descriptor.catalog` baked into `descriptor.json` is preserved.
     */
    private fun applyCatalogOverlay(modelId: String, base: ModelDescriptor): ModelDescriptor {
        val authored = readInJarCatalog(modelId) ?: return base
        val problems = CatalogValidation.validate(authored, base)
        if (problems.isNotEmpty()) {
            logger.warn {
                "catalog.toml for ${bundle.bundleId}/$modelId has ${problems.size} issue(s): " +
                        problems.joinToString("; ") { it.message }
            }
        }
        return base.copy(catalog = CatalogValidation.sanitize(authored, base))
    }

    private fun readInJarCatalog(modelId: String): ModelCatalog? {
        val path = BundleLayout.catalogPath(modelId)
        val stream = classLoader.getResourceAsStream(path) ?: return null
        return try {
            stream.use { input -> ModelCatalogToml.decode(input.bufferedReader().readText()) }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read in-JAR catalog at $path for ${bundle.bundleId}/$modelId" }
            null
        }
    }

    /**
     * Returns the raw, un-sanitized catalog authored in the JAR at
     * [BundleLayout.catalogPath], or `null` when no `catalog.toml` is present.
     * Unlike the catalog reached through [descriptorFor] (which is validated and
     * sanitized for overlay), this is the as-authored catalog — exposed so
     * validation tooling can report problems before they are silently dropped.
     *
     * @throws IllegalStateException if this `LoadedBundle` has already been closed
     */
    fun inJarCatalog(modelId: String): ModelCatalog? {
        check(!closed) { "LoadedBundle ${bundle.bundleId} has been closed" }
        return readInJarCatalog(modelId)
    }

    private fun memoize(modelId: String, descriptor: ModelDescriptor): ModelDescriptor {
        myDescriptors.putIfAbsent(modelId, descriptor)
        return myDescriptors[modelId] ?: descriptor
    }

    private fun readInJarDescriptor(modelId: String): ModelDescriptor? {
        val path = BundleLayout.descriptorPath(modelId)
        val stream = classLoader.getResourceAsStream(path) ?: return null
        return try {
            stream.use { input ->
                myJson.decodeFromString(ModelDescriptor.serializer(), input.bufferedReader().readText())
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read in-JAR descriptor at $path for ${bundle.bundleId}/$modelId" }
            null
        }
    }

    /**
     * Releases the `AutoCloseable` resources this instance owns (typically a
     * `URLClassLoader`); a no-op when `ownedResources` is `null`. After
     * `close`, calls to `descriptorFor` throw `IllegalStateException`.
     */
    override fun close() {
        if (closed) return
        closed = true
        val resources = ownedResources ?: return
        try {
            resources.close()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to close resources for bundle ${bundle.bundleId}" }
        }
    }
}

/**
 * Thrown when a loaded bundle declares a model but embeds no descriptor for it —
 * i.e. the JAR is not a fully-assembled bundle. The message points the user to the
 * bundling step, which is what makes a JAR loadable.
 */
class IncompleteBundleException(val bundleId: String, val modelId: String) : RuntimeException(
    "Bundle '$bundleId' is incomplete: model '$modelId' has no embedded descriptor at " +
        "${BundleLayout.descriptorPath(modelId)}. Re-assemble it with 'kslpkg assemble' or " +
        "the Bundle Workbench before loading."
)
