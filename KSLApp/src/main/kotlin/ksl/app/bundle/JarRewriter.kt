package ksl.app.bundle

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Writes a copy of a JAR with a set of in-JAR entries added or replaced. This is the
 * single, shared JAR-rewrite implementation used by both `kslpkg enrich` (legacy
 * bundle JARs) and [BundleAssembler] (builders JARs → bundle JARs).
 *
 * Properties relied on by callers:
 *  - **Input is read-only.** [input] is opened only for reading; it is never
 *    modified. (Callers that want atomicity write to a temp path and move it into
 *    place; this object does not move files.)
 *  - **Byte-stable layout.** Copied and appended entries get an epoch timestamp, and
 *    new entries are appended in sorted-path order, so the same inputs yield the same
 *    bytes (modulo any [manifestAttributes] a caller injects, e.g. a build time).
 *  - **Manifest re-emit.** The manifest is re-emitted from the parsed `Manifest`
 *    (augmented with [manifestAttributes]); the original `META-INF/MANIFEST.MF`
 *    entry is dropped from the copy loop and rewritten by the `JarOutputStream`.
 *  - **No duplicates on re-run.** Any pre-existing entry whose path is in
 *    [newEntries] is dropped from the copy, so re-running does not duplicate entries.
 */
object JarRewriter {

    /**
     * Streams [input] to [output], skipping the manifest and any entry whose path is
     * in [newEntries], then appends [newEntries] in sorted-path order. When
     * [manifestAttributes] is non-empty they are merged into the main manifest
     * section (a `Manifest-Version` is supplied if the input had no manifest).
     */
    fun rewrite(
        input: Path,
        output: Path,
        newEntries: Map<String, ByteArray>,
        manifestAttributes: Map<String, String> = emptyMap(),
    ) {
        JarFile(input.toFile()).use { jar ->
            val manifest: Manifest = (jar.manifest ?: Manifest()).also { m ->
                if (manifestAttributes.isNotEmpty()) {
                    if (m.mainAttributes.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
                        m.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                    }
                    for ((k, v) in manifestAttributes) m.mainAttributes.putValue(k, v)
                }
            }
            Files.newOutputStream(output).use { os ->
                JarOutputStream(os, manifest).use { jarOut ->
                    val replacePaths = newEntries.keys
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val src = entries.nextElement()
                        val name = src.name
                        if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                        if (name in replacePaths) continue
                        jarOut.putNextEntry(JarEntry(name).apply { time = 0L })
                        if (!src.isDirectory) {
                            jar.getInputStream(src).use { it.copyTo(jarOut) }
                        }
                        jarOut.closeEntry()
                    }
                    for (path in newEntries.keys.sorted()) {
                        jarOut.putNextEntry(JarEntry(path).apply { time = 0L })
                        jarOut.write(newEntries.getValue(path))
                        jarOut.closeEntry()
                    }
                }
            }
        }
    }
}
