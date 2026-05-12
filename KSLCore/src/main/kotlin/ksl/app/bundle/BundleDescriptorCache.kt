package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.simulation.ModelDescriptor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * On-disk cache of lazily-extracted `ksl.simulation.ModelDescriptor` JSON for
 * bundles that were not pre-enriched by `kslpkg enrich`. Entries are keyed by
 * the SHA-256 of the bundle's source JAR so replacing the JAR automatically
 * invalidates the cache.
 *
 * Cache layout under `rootDir`:
 *
 *   <rootDir>/<sha256-of-jar>/meta.json     (cacheSchemaVersion, writtenAt)
 *   <rootDir>/<sha256-of-jar>/<modelId>.json
 *
 * The cache is best-effort: any I/O error during `read` or `write` is logged
 * and swallowed. A failing cache never breaks the loader — the descriptor is
 * recomputed on the next load.
 *
 * @property rootDir the directory under which per-JAR cache entries live;
 * defaults to `~/.ksl/bundle-cache`
 */
class BundleDescriptorCache(
    val rootDir: Path = defaultCacheDir()
) {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    @Serializable
    private data class CacheMeta(
        val cacheSchemaVersion: Int,
        val writtenAt: String
    )

    /**
     * Returns the cached descriptor for the given (JAR-hash, modelId) pair,
     * or `null` on any kind of miss: no cache entry, schema-version mismatch,
     * malformed JSON, or I/O error.
     */
    fun read(jarSha256: String, modelId: String): ModelDescriptor? {
        val dir = rootDir.resolve(jarSha256)
        val metaFile = dir.resolve("meta.json")
        val descFile = dir.resolve("$modelId.json")
        if (!Files.isRegularFile(metaFile) || !Files.isRegularFile(descFile)) return null
        return try {
            val meta = myJson.decodeFromString(CacheMeta.serializer(), Files.readString(metaFile))
            if (meta.cacheSchemaVersion != CACHE_SCHEMA_VERSION) {
                logger.info { "Discarding cache entry $jarSha256: schema ${meta.cacheSchemaVersion} != $CACHE_SCHEMA_VERSION" }
                return null
            }
            myJson.decodeFromString(ModelDescriptor.serializer(), Files.readString(descFile))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read bundle-cache entry $jarSha256/$modelId" }
            null
        }
    }

    /**
     * Writes the descriptor into the cache. Failures are logged and swallowed;
     * an unwritable cache directory never prevents the caller from proceeding.
     */
    fun write(jarSha256: String, modelId: String, descriptor: ModelDescriptor) {
        val dir = rootDir.resolve(jarSha256)
        try {
            Files.createDirectories(dir)
            val meta = CacheMeta(CACHE_SCHEMA_VERSION, java.time.Instant.now().toString())
            Files.writeString(dir.resolve("meta.json"), myJson.encodeToString(CacheMeta.serializer(), meta))
            Files.writeString(
                dir.resolve("$modelId.json"),
                myJson.encodeToString(ModelDescriptor.serializer(), descriptor)
            )
        } catch (e: IOException) {
            logger.warn(e) { "Failed to write bundle-cache entry $jarSha256/$modelId" }
        }
    }

    companion object {

        /**
         * Version of the cache directory schema. Bumped whenever the on-disk
         * layout (file names, meta-file shape, or `ModelDescriptor` JSON
         * structure) changes in a way that would mis-deserialize older entries.
         */
        const val CACHE_SCHEMA_VERSION: Int = 1

        /** Returns the conventional cache root, `~/.ksl/bundle-cache`. */
        fun defaultCacheDir(): Path =
            Paths.get(System.getProperty("user.home"), ".ksl", "bundle-cache")

        /**
         * Streams the file's bytes through SHA-256 and returns the digest as
         * a lower-case hex string. Used as the per-JAR cache directory name.
         */
        fun sha256OfFile(path: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
