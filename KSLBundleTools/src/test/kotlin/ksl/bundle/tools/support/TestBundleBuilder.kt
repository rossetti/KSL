package ksl.bundle.tools.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Builds a real bundle JAR file in a caller-supplied directory by copying
 * the compiled `.class` files of one or more `KSLModelBundle` implementations
 * out of the running test's classpath and emitting an appropriate
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` registration.
 *
 * Inner classes (Kotlin `private object` declarations like `StubBundle.StubModel`)
 * are copied alongside the outer class.
 *
 * The produced JAR is self-sufficient *as a bundle declaration* — it does
 * not embed KSLCore classes, which are expected to resolve through the
 * test classpath (URLClassLoader parent delegation, just like in production).
 */
internal object TestBundleBuilder {

    /**
     * Builds a JAR at `<dir>/<name>.jar` containing every class file from
     * each bundle class's package-or-deeper, plus a services file listing
     * the bundle classes.
     *
     * @return the absolute path to the produced JAR
     */
    fun build(dir: Path, name: String, bundleClasses: List<Class<*>>): Path {
        val target = dir.resolve("$name.jar")
        Files.newOutputStream(target).use { os ->
            JarOutputStream(os, Manifest()).use { jar ->
                val seen = mutableSetOf<String>()
                for (cls in bundleClasses) {
                    addClassWithInnerClasses(jar, cls, seen)
                }
                // META-INF/services registration
                val servicesPath = "META-INF/services/ksl.app.bundle.KSLModelBundle"
                if (servicesPath !in seen) {
                    val entry = JarEntry(servicesPath).apply { time = 0L }
                    jar.putNextEntry(entry)
                    val body = bundleClasses.joinToString("\n") { it.name } + "\n"
                    jar.write(body.toByteArray(Charsets.UTF_8))
                    jar.closeEntry()
                    seen += servicesPath
                }
            }
        }
        return target
    }

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
