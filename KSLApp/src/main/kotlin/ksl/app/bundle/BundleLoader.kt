package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}

/**
 * Entry point for discovering and loading `KSLModelBundle` instances.
 *
 * Three sources are supported:
 *   - `loadJar`         — a single JAR file
 *   - `loadDirectory`   — every `.jar` file directly inside a directory
 *   - `loadFromClasspath` — bundles already visible to a `ClassLoader` on the
 *                          running JVM's classpath
 *
 * Two discovery mechanisms are supported, checked in this order:
 *   1. **Manifest-driven (data-driven) bundles.** When a JAR (or a classpath
 *      entry) carries a `BundleLayout.BUNDLE_TOML` manifest, the loader decodes it
 *      and builds a single reusable `ManifestBackedBundle` from it. One
 *      `bundle.toml` declares one bundle. This is how bundle JARs produced by the
 *      enrichment tooling are loaded; the JAR needs no `META-INF/services` file and
 *      no compiled `KSLModelBundle` class.
 *   2. **Legacy `ServiceLoader` bundles.** When no manifest is present, discovery
 *      falls back to `java.util.ServiceLoader` against
 *      `META-INF/services/ksl.app.bundle.KSLModelBundle` (hand-written
 *      `KSLModelBundle` implementations). JARs with neither a manifest nor a
 *      services registration are not bundles; `loadJar` returns an empty list.
 *
 * Pre-bundle JARs holding bare `ksl.simulation.ModelBuilderIfc` classes (with no
 * manifest) are loaded through the separate `ksl.utilities.io.JARModelBuilder` API
 * instead.
 *
 * `loadJar` and `loadDirectory` create a fresh `URLClassLoader` per JAR and
 * hand it to each discovered `LoadedBundle`; the bundles' `close` releases it.
 * If a single JAR declares multiple bundles, all returned `LoadedBundle`s
 * share that classloader and should be closed as a group.
 */
object BundleLoader {

    /**
     * Loads every bundle declared in `jarPath` via `ServiceLoader`. Each
     * returned `LoadedBundle` owns the freshly-created classloader; if the
     * JAR declares multiple bundles they share the loader.
     *
     * @param jarPath path to a regular JAR file
     * @param parent parent classloader for delegation; defaults to the loader
     *               that holds KSLCore so bundle code resolves KSL types
     * @param cache  on-disk descriptor cache for lazy extraction; defaults
     *               to one rooted at `~/.ksl/bundle-cache`
     * @return zero or more bundles (empty if the JAR has no services file
     *         registration for `KSLModelBundle`)
     */
    fun loadJar(
        jarPath: Path,
        parent: ClassLoader = defaultParent(),
        cache: BundleDescriptorCache = BundleDescriptorCache()
    ): List<LoadedBundle> {
        require(jarPath.isRegularFile()) { "Not a regular file: $jarPath" }
        val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), parent)
        val sha = BundleDescriptorCache.sha256OfFile(jarPath)
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
                    cache = cache,
                    builtAt = builtAt,
                )
            )
        }

        // 2. Legacy ServiceLoader-discovered KSLModelBundle implementations.
        val discovered = ServiceLoader.load(KSLModelBundle::class.java, classLoader).toList()

        if (discovered.isEmpty()) {
            classLoader.close()
            logger.info { "No KSLModelBundle providers in $jarPath" }
            return emptyList()
        }

        return discovered.map { bundle ->
            LoadedBundle(
                bundle = bundle,
                sourceJar = jarPath,
                classLoader = classLoader,
                ownedResources = classLoader,
                jarSha256 = sha,
                cache = cache,
                builtAt = builtAt
            )
        }
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
     * logged and treated as absent (so loading can fall back to ServiceLoader)
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
     * Loads bundles from every `.jar` file directly inside `dir` (non-recursive).
     * Empty list if the directory is missing or contains no JARs. JARs whose
     * `loadJar` throws are skipped with a warning so one bad bundle never
     * breaks startup discovery.
     */
    fun loadDirectory(
        dir: Path,
        parent: ClassLoader = defaultParent(),
        cache: BundleDescriptorCache = BundleDescriptorCache()
    ): List<LoadedBundle> {
        if (!dir.isDirectory()) return emptyList()
        val result = mutableListOf<LoadedBundle>()
        Files.newDirectoryStream(dir, "*.jar").use { stream ->
            for (jar in stream.sorted()) {
                try {
                    result += loadJar(jar, parent, cache)
                } catch (e: Exception) {
                    logger.warn(e) { "Skipping bundle JAR $jar: ${e.message}" }
                }
            }
        }
        return result
    }

    /**
     * Loads bundles already visible to `classLoader` (default: the loader
     * that holds KSLCore). Used to surface in-process bundled examples that
     * ship as part of the application's classpath rather than as separate
     * JAR files. Returned bundles have `sourceJar == null` and bypass the
     * on-disk cache; in-JAR descriptors are still consulted via
     * `getResourceAsStream`.
     */
    fun loadFromClasspath(
        classLoader: ClassLoader = defaultParent(),
        cache: BundleDescriptorCache = BundleDescriptorCache()
    ): List<LoadedBundle> {
        val result = mutableListOf<LoadedBundle>()

        // 1. Manifest-driven bundles visible on the classpath (there may be several,
        //    one per bundle.toml resource). sourceJar/jarSha256/builtAt are null, as
        //    for any classpath-loaded bundle.
        for (url in Collections.list(classLoader.getResources(BundleLayout.BUNDLE_TOML))) {
            val manifest = runCatching {
                url.openStream().use { BundleManifestToml.decode(it.readBytes().toString(Charsets.UTF_8)) }
            }.getOrElse { e ->
                logger.warn(e) { "Malformed ${BundleLayout.BUNDLE_TOML} at $url; ignoring" }
                null
            } ?: continue
            result += LoadedBundle(
                bundle = ManifestBackedBundle(classLoader, manifest),
                sourceJar = null,
                classLoader = classLoader,
                ownedResources = null,
                jarSha256 = null,
                cache = cache
            )
        }

        // 2. Legacy ServiceLoader-discovered bundles on the classpath.
        ServiceLoader.load(KSLModelBundle::class.java, classLoader).toList().forEach { bundle ->
            result += LoadedBundle(
                bundle = bundle,
                sourceJar = null,
                classLoader = classLoader,
                ownedResources = null,
                jarSha256 = null,
                cache = cache
            )
        }
        return result
    }

    /** Default parent classloader: the one that loaded KSLCore. */
    fun defaultParent(): ClassLoader =
        BundleLoader::class.java.classLoader

    /** Convenience predicate, useful in tests and tooling. */
    fun isJar(path: Path): Boolean =
        path.isRegularFile() && path.extension.equals("jar", ignoreCase = true)
}
