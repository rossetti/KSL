package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}

/**
 * Entry point for discovering and loading `KSLModelBundle` instances.
 *
 * Two sources are supported:
 *   - `loadJar`         — a single JAR file
 *   - `loadDirectory`   — every `.jar` file directly inside a directory
 *
 * Bundles are manifest-driven. When a JAR carries a `BundleLayout.BUNDLE_TOML`
 * manifest, the loader decodes it and builds a single reusable
 * `ManifestBackedBundle` from it — one `bundle.toml` declares one bundle, and the
 * JAR needs no compiled `KSLModelBundle` class. A JAR with no manifest is not a
 * bundle; `loadJar` returns an empty list. (Legacy `ServiceLoader` discovery
 * against `META-INF/services/ksl.app.bundle.KSLModelBundle` has been retired.)
 *
 * Pre-bundle JARs holding bare `ksl.simulation.ModelBuilderIfc` classes (with no
 * manifest) are loaded through the separate `ksl.utilities.io.JARModelBuilder` API
 * instead.
 *
 * `loadJar` and `loadDirectory` create a fresh `URLClassLoader` per JAR and
 * hand it to the `LoadedBundle`; the bundle's `close` releases it.
 */
object BundleLoader {

    /**
     * Loads the manifest-driven bundle declared in `jarPath`. A JAR carries a
     * bundle when it contains a `bundle.toml` manifest; the loader decodes it
     * into a single `ManifestBackedBundle` that owns the freshly-created
     * classloader.
     *
     * @param jarPath path to a regular JAR file
     * @param parent parent classloader for delegation; defaults to the loader
     *               that holds KSLCore so bundle code resolves KSL types
     * @return a single-element list with the manifest bundle, or empty when the
     *         JAR has no `bundle.toml` manifest
     *
     * This is the lenient primitive: it loads any manifest-bearing JAR, complete
     * or not, so tooling (`kslpkg inspect`, authoring validation) can read it. The
     * runtime "must be a complete bundle" gate lives in [loadForConsumption] /
     * [loadDirectory], which the server and apps use.
     */
    fun loadJar(
        jarPath: Path,
        parent: ClassLoader = defaultParent(),
    ): List<LoadedBundle> {
        require(jarPath.isRegularFile()) { "Not a regular file: $jarPath" }
        val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), parent)
        val sha = sha256OfFile(jarPath)
        val builtAt = readBuiltAt(jarPath)

        // 1. Manifest-driven bundle: one bundle.toml = one bundle. Read the manifest
        //    bytes directly from this JAR (not via the classloader) so a manifest on
        //    the parent classpath cannot be mistaken for this JAR's.
        readManifestFromJar(jarPath)?.let { manifest ->
            return listOf(
                LoadedBundle(
                    bundle = ManifestBackedBundle(classLoader, manifest),
                    sourceJar = jarPath,
                    classLoader = classLoader,
                    ownedResources = classLoader,
                    jarSha256 = sha,
                    builtAt = builtAt,
                )
            )
        }

        // No bundle.toml manifest: not a bundle JAR.  (Legacy ServiceLoader
        // discovery has been retired — bundles are manifest-driven only.)
        classLoader.close()
        logger.info { "No bundle.toml manifest in $jarPath; not a bundle JAR" }
        return emptyList()
    }

    /**
     *  Best-effort build timestamp for a bundle JAR: the manifest's
     *  `Build-Time` attribute (written by the KSL bundle-packaging tasks) when
     *  present and parseable, otherwise the JAR file's last-modified time.
     *  `null` only if the file cannot be read at all.  Drives newest-wins
     *  resolution of same-`(bundleId, version)` duplicates.
     */
    private fun readBuiltAt(jarPath: Path): Instant? {
        runCatching {
            JarFile(jarPath.toFile()).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Build-Time")
            }
        }.getOrNull()?.let { stamp ->
            runCatching { Instant.parse(stamp) }.getOrNull()?.let { return it }
        }
        return runCatching { Files.getLastModifiedTime(jarPath).toInstant() }.getOrNull()
    }

    /**
     * Reads and decodes the [BundleLayout.BUNDLE_TOML] manifest directly from
     * [jarPath], or `null` if the JAR has no manifest. A malformed manifest is
     * logged and treated as absent (so the JAR is reported as not-a-bundle)
     * rather than throwing.
     */
    private fun readManifestFromJar(jarPath: Path): BundleManifest? {
        val text = runCatching {
            JarFile(jarPath.toFile()).use { jar ->
                jar.getJarEntry(BundleLayout.BUNDLE_TOML)?.let { entry ->
                    jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }
        }.getOrNull() ?: return null
        return try {
            BundleManifestToml.decode(text)
        } catch (e: Exception) {
            logger.warn(e) { "Malformed ${BundleLayout.BUNDLE_TOML} in $jarPath; ignoring manifest" }
            null
        }
    }

    /**
     * Loads every **complete** bundle from the `.jar` files directly inside `dir`
     * (non-recursive) for runtime consumption, and reports the JARs it rejected.
     * A JAR is rejected when it is not a bundle (no `bundle.toml`), is an
     * incomplete bundle (missing in-JAR descriptors — not fully assembled), or
     * fails to load; rejected bundles' classloaders are closed. A missing
     * directory yields an empty outcome.
     */
    fun loadDirectory(
        dir: Path,
        parent: ClassLoader = defaultParent(),
    ): LoadOutcome {
        if (!dir.isDirectory()) return LoadOutcome(emptyList(), emptyList())
        val loaded = mutableListOf<LoadedBundle>()
        val rejected = mutableListOf<RejectedJar>()
        Files.newDirectoryStream(dir, "*.jar").use { stream ->
            for (jar in stream.sorted()) {
                val outcome = loadForConsumption(jar, parent)
                loaded += outcome.loaded
                rejected += outcome.rejected
            }
        }
        return LoadOutcome(loaded, rejected)
    }

    /**
     * Loads `jarPath` for runtime consumption, enforcing the "complete bundle"
     * contract: a JAR with no `bundle.toml` is rejected as not-a-bundle, a manifest
     * bundle missing any in-JAR descriptor is rejected as incomplete (and its
     * classloader closed), and a load failure is rejected with its message. Only
     * complete bundles appear in [LoadOutcome.loaded].
     */
    fun loadForConsumption(jarPath: Path, parent: ClassLoader = defaultParent()): LoadOutcome {
        val bundles = try {
            loadJar(jarPath, parent)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load bundle JAR $jarPath: ${e.message}" }
            return LoadOutcome(emptyList(), listOf(RejectedJar(jarPath, "failed to load: ${e.message}")))
        }
        if (bundles.isEmpty()) {
            return LoadOutcome(emptyList(), listOf(RejectedJar(jarPath, notABundleReason)))
        }
        val loaded = mutableListOf<LoadedBundle>()
        val rejected = mutableListOf<RejectedJar>()
        for (lb in bundles) {
            val missing = lb.missingDescriptors()
            if (missing.isEmpty()) {
                loaded += lb
            } else {
                rejected += RejectedJar(
                    jarPath,
                    "incomplete bundle '${lb.bundle.bundleId}': missing embedded descriptor(s) for " +
                        "${missing.joinToString()}; re-assemble it with 'kslpkg assemble' or the Bundle Workbench",
                )
                runCatching { lb.close() }
            }
        }
        return LoadOutcome(loaded, rejected)
    }

    /** Default parent classloader: the one that loaded KSLCore. */
    fun defaultParent(): ClassLoader =
        BundleLoader::class.java.classLoader

    /** Convenience predicate, useful in tests and tooling. */
    fun isJar(path: Path): Boolean =
        path.isRegularFile() && path.extension.equals("jar", ignoreCase = true)

    /** SHA-256 of a file's bytes as lower-case hex — the per-JAR content hash used
     *  for newest-wins dedup and result-cache version salting. */
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

    private val notABundleReason: String =
        "not a KSL bundle (no ${BundleLayout.BUNDLE_TOML} manifest); assemble it with " +
            "'kslpkg assemble' or the Bundle Workbench"
}

/** A JAR the runtime loader refused, with a user-facing [reason]. */
data class RejectedJar(val jar: Path, val reason: String)

/** The result of loading a directory or JAR for consumption: the complete bundles
 *  that loaded and the JARs that were rejected. */
data class LoadOutcome(val loaded: List<LoadedBundle>, val rejected: List<RejectedJar>)
