package ksl.bundle.tools.support

import ksl.app.bundle.BundleLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Builds plain "builders" JAR files in a caller-supplied directory by copying the
 * compiled `.class` files of one or more classes out of the running test's
 * classpath.  These JARs hold `ModelBuilderIfc` implementations (and any inner
 * classes) ready to be assembled into a manifest bundle via `kslpkg assemble`; the
 * helper does not write a `bundle.toml` itself.
 *
 * The produced JAR is self-sufficient *as a class container* — it does not embed
 * KSLCore classes, which are expected to resolve through the test classpath
 * (URLClassLoader parent delegation, just like in production).
 */
internal object TestBundleBuilder {

    /**
     * Variant that emits the class files but no
     * `META-INF/services/ksl.app.bundle.KSLModelBundle` entry. Used to
     * exercise the empty-discovery path: a JAR without a bundle
     * registration is not a bundle and `BundleLoader.loadJar` returns
     * an empty list.
     */
    fun buildWithoutServicesFile(dir: Path, name: String, classes: List<Class<*>>): Path {
        val target = dir.resolve("$name.jar")
        Files.newOutputStream(target).use { os ->
            JarOutputStream(os, Manifest()).use { jar ->
                val seen = mutableSetOf<String>()
                for (cls in classes) {
                    addClassWithInnerClasses(jar, cls, seen)
                }
            }
        }
        return target
    }

    /**
     * Copies [src] to a sibling JAR with every per-model `descriptor.json` removed,
     * producing a manifest bundle that carries no in-JAR descriptor — so loading it
     * must fall through to tier-3 lazy extraction.
     */
    fun stripDescriptors(src: Path): Path {
        val dst = src.resolveSibling(src.fileName.toString().removeSuffix(".jar") + "-nodesc.jar")
        JarFile(src.toFile()).use { jf ->
            JarOutputStream(Files.newOutputStream(dst), Manifest()).use { out ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    // JarOutputStream(Manifest()) already wrote META-INF/MANIFEST.MF.
                    if (entry.name == "META-INF/MANIFEST.MF") continue
                    if (entry.name.startsWith(BundleLayout.MODELS_ROOT) && entry.name.endsWith("/descriptor.json")) continue
                    out.putNextEntry(JarEntry(entry.name).apply { time = 0L })
                    jf.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        return dst
    }

    private fun addClassWithInnerClasses(
        jar: JarOutputStream,
        cls: Class<*>,
        seen: MutableSet<String>
    ) {
        addClass(jar, cls, seen)
        // Sibling files in the same package whose names begin with "<cls.simpleName>$"
        // (Kotlin inner objects, anonymous classes from the bundle's builder lambdas)
        val pkgPath = cls.`package`.name.replace('.', '/')
        val loader = cls.classLoader
        val pkgDir = loader.getResource(pkgPath)?.let {
            try { java.nio.file.Paths.get(it.toURI()) } catch (_: Exception) { null }
        } ?: return
        if (!Files.isDirectory(pkgDir)) return
        Files.list(pkgDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("${cls.simpleName}\$") }
                .filter { it.fileName.toString().endsWith(".class") }
                .forEach { siblingPath ->
                    val entryName = "$pkgPath/${siblingPath.fileName}"
                    if (entryName !in seen) {
                        val entry = JarEntry(entryName).apply { time = 0L }
                        jar.putNextEntry(entry)
                        jar.write(Files.readAllBytes(siblingPath))
                        jar.closeEntry()
                        seen += entryName
                    }
                }
        }
    }

    private fun addClass(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        val entryName = cls.name.replace('.', '/') + ".class"
        if (entryName in seen) return
        val bytes = cls.classLoader.getResourceAsStream(entryName)?.use { it.readBytes() }
            ?: error("Cannot locate class file for ${cls.name} on the test classpath")
        val entry = JarEntry(entryName).apply { time = 0L }
        jar.putNextEntry(entry)
        jar.write(bytes)
        jar.closeEntry()
        seen += entryName
    }
}
