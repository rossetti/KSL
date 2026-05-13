package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
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
 * Discovery uses the standard `java.util.ServiceLoader` mechanism against
 * `META-INF/services/ksl.app.bundle.KSLModelBundle`. JARs without a services
 * registration are not bundles; `loadJar` returns an empty list for them.
 * Pre-bundle JARs holding bare `ksl.simulation.ModelBuilderIfc` classes are
 * loaded through the separate `ksl.utilities.io.JARModelBuilder` API instead.
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
                cache = cache
            )
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
        return ServiceLoader.load(KSLModelBundle::class.java, classLoader).toList().map { bundle ->
            LoadedBundle(
                bundle = bundle,
                sourceJar = null,
                classLoader = classLoader,
                ownedResources = null,
                jarSha256 = null,
                cache = cache
            )
        }
    }

    /** Default parent classloader: the one that loaded KSLCore. */
    fun defaultParent(): ClassLoader =
        BundleLoader::class.java.classLoader

    /** Convenience predicate, useful in tests and tooling. */
    fun isJar(path: Path): Boolean =
        path.isRegularFile() && path.extension.equals("jar", ignoreCase = true)
}
