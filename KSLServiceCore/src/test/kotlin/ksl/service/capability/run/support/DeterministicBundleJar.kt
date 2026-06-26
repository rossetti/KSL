package ksl.service.capability.run.support

import ksl.app.bundle.BundleLayout
import ksl.app.bundle.KSLAppKind
import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import ksl.app.config.ModelManifestEntry
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Deterministic manifest-bundle JAR builder for the registry mechanics tests
 * (dedup / version invalidation). Unlike the production assembler — which stamps a
 * unique `Build-Time = Instant.now()` into every jar via `BundleAssembler` — this
 * hand-writes the builder class(es) plus a `bundle.toml` with fixed `time = 0L`
 * entries in a stable order and writes NO JAR manifest at all, so:
 *
 *   - byte-identical arguments produce a byte-identical jar (hence the same content
 *     hash), which the version-invalidation test relies on; and
 *   - with no `Build-Time` attribute, `ksl.app.bundle.BundleLoader.readBuiltAt`
 *     falls back to the file mtime, which the dedup test controls via
 *     `Files.setLastModifiedTime`.
 *
 * This is the manifest-era replacement for the retired ServiceLoader `TestBundleBuilder`:
 * the only mechanism that lets these tests craft duplicate / rebuilt / time-ordered jars.
 * (A jar needs no `META-INF/MANIFEST.MF` to be loaded — the bundle is carried by `bundle.toml`.)
 */
internal object DeterministicBundleJar {

    /**
     * Writes `<dir>/<jarName>.jar` declaring [bundleId] with a single model [modelId]
     * built by [builderClass]. [extraEntries] (e.g. a content marker) and [version] vary
     * the bytes — hence the content hash; identical arguments are byte-reproducible.
     */
    fun build(
        dir: Path,
        jarName: String,
        bundleId: String,
        builderClass: Class<out ModelBuilderIfc>,
        modelId: String = "MM1",
        version: String = "1.0.0",
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): Path {
        val manifest = BundleManifest(
            bundleId = bundleId,
            displayName = bundleId,
            description = "Deterministic test bundle.",
            version = version,
            models = listOf(
                ModelManifestEntry(
                    modelId = modelId,
                    builderClass = builderClass.name,
                    displayName = modelId,
                    supportedApps = setOf(KSLAppKind.SINGLE),
                ),
            ),
        )
        val target = dir.resolve("$jarName.jar")
        JarOutputStream(Files.newOutputStream(target)).use { jar ->
            writeClassWithInnerClasses(jar, builderClass)
            writeEntry(jar, BundleLayout.BUNDLE_TOML, BundleManifestToml.encode(manifest).toByteArray(Charsets.UTF_8))
            extraEntries.toSortedMap().forEach { (name, bytes) -> writeEntry(jar, name, bytes) }
        }
        return target
    }

    /** The builder's class file plus its synthetic `<simpleName>$*` siblings, in sorted (stable) order. */
    private fun writeClassWithInnerClasses(jar: JarOutputStream, cls: Class<*>) {
        val mainEntry = cls.name.replace('.', '/') + ".class"
        writeEntry(jar, mainEntry, classBytes(cls.classLoader, mainEntry))
        val pkgPath = cls.`package`.name.replace('.', '/')
        val pkgDir = cls.classLoader.getResource(pkgPath)
            ?.let { runCatching { Paths.get(it.toURI()) }.getOrNull() }
            ?: return
        if (!Files.isDirectory(pkgDir)) return
        Files.list(pkgDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("${cls.simpleName}\$") }
                .filter { it.fileName.toString().endsWith(".class") }
                .sorted()
                .forEach { sibling -> writeEntry(jar, "$pkgPath/${sibling.fileName}", Files.readAllBytes(sibling)) }
        }
    }

    private fun classBytes(loader: ClassLoader, entryName: String): ByteArray =
        loader.getResourceAsStream(entryName)?.use { it.readBytes() }
            ?: error("Cannot locate class file '$entryName' on the test classpath")

    private fun writeEntry(jar: JarOutputStream, name: String, bytes: ByteArray) {
        jar.putNextEntry(JarEntry(name).apply { time = 0L })
        jar.write(bytes)
        jar.closeEntry()
    }
}
