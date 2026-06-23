package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import ksl.simulation.ModelDescriptor
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Runtime-managed wrapper around one discovered `KSLModelBundle`. Holds the
 * bundle's classloader and (when applicable) its source JAR and content hash,
 * and provides on-demand access to each model's `ksl.simulation.ModelDescriptor`
 * with a three-tier resolution strategy.
 *
 * Descriptor resolution priority (per `descriptorFor`):
 *   1. In-JAR resource at `BundleLayout.descriptorPath(modelId)`. Present when
 *      the bundle was processed by `kslpkg enrich`.
 *   2. On-disk cache at `~/.ksl/bundle-cache/<jarSha256>/<modelId>.json`.
 *      Applies only to JAR-backed bundles, not classpath-loaded ones.
 *   3. Lazy extraction: instantiate the model via its builder and call
 *      `Model.modelDescriptor()`. The result is cached for JAR-backed
 *      bundles before returning.
 *
 * Results are memoized in-memory for the lifetime of this `LoadedBundle`.
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
    private val cache: BundleDescriptorCache,
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
     * Returns the `ModelDescriptor` for the given `modelId`. Resolves through
     * in-JAR resource, then on-disk cache, then lazy extraction (see class
     * KDoc). The first successful resolution is memoized.
     *
     * @throws IllegalArgumentException if `modelId` is not declared by this bundle
     * @throws IllegalStateException if this `LoadedBundle` has already been closed
     * @throws RuntimeException if lazy extraction fails (model build error)
     */
    fun descriptorFor(modelId: String): ModelDescriptor {
        check(!closed) { "LoadedBundle ${bundle.bundleId} has been closed" }
        myDescriptors[modelId]?.let { return it }

        val bundledModel = bundle.models.firstOrNull { it.modelId == modelId }
            ?: throw IllegalArgumentException(
                "Bundle ${bundle.bundleId} does not declare model '$modelId'. " +
                        "Available: ${bundle.models.map { it.modelId }}"
            )

        // 1. In-JAR descriptor
        readInJarDescriptor(modelId)?.let { return memoize(modelId, it) }

        // 2. On-disk cache (JAR-backed bundles only)
        if (jarSha256 != null) {
            cache.read(jarSha256, modelId)?.let { return memoize(modelId, it) }
        }

        // 3. Lazy extraction
        val descriptor = try {
            bundledModel.builder().build(null, null).modelDescriptor()
        } catch (e: Exception) {
            throw RuntimeException(
                "Failed to extract descriptor for ${bundle.bundleId}/$modelId", e
            )
        }
        if (jarSha256 != null) {
            cache.write(jarSha256, modelId, descriptor)
        }
        return memoize(modelId, descriptor)
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
