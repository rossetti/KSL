package ksl.app.swing.bundle.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Minimal bundle-JAR builder for tests: copies a `KSLModelBundle` class (and its
 * inner classes) off the test classpath and emits the `ServiceLoader`
 * registration. KSLCore classes resolve through the parent classloader at load
 * time, exactly as in production.
 */
internal object TestJarBuilder {

    fun build(dir: Path, name: String, bundleClass: Class<*>): Path {
        val target = dir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            val seen = mutableSetOf<String>()
            addClassTree(jar, bundleClass, seen)
            val services = "META-INF/services/ksl.app.bundle.KSLModelBundle"
            jar.putNextEntry(JarEntry(services).apply { time = 0L })
            jar.write((bundleClass.name + "\n").toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
        return target
    }

    /**
     * Builds a plain **builders JAR**: the given `ModelBuilderIfc` class trees only,
     * with no manifest and no `META-INF/services` registration — the model author's
     * deliverable that the workbench/`assemble` turns into a bundle JAR.
     */
    fun buildBuildersJar(dir: Path, name: String, vararg builderClasses: Class<*>): Path {
        val target = dir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            val seen = mutableSetOf<String>()
            builderClasses.forEach { addClassTree(jar, it, seen) }
        }
        return target
    }

    private fun addClassTree(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        addEntry(jar, cls.name.replace('.', '/') + ".class",
            cls.classLoader.getResourceAsStream(cls.name.replace('.', '/') + ".class")!!.use { it.readBytes() }, seen)
        val pkgPath = cls.`package`.name.replace('.', '/')
        val pkgDir = cls.classLoader.getResource(pkgPath)?.let {
            try { Paths.get(it.toURI()) } catch (_: Exception) { null }
        } ?: return
        if (!Files.isDirectory(pkgDir)) return
        Files.list(pkgDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("${cls.simpleName}\$") && it.fileName.toString().endsWith(".class") }
                .forEach { addEntry(jar, "$pkgPath/${it.fileName}", Files.readAllBytes(it), seen) }
        }
    }

    private fun addEntry(jar: JarOutputStream, name: String, bytes: ByteArray, seen: MutableSet<String>) {
        if (name in seen) return
        jar.putNextEntry(JarEntry(name).apply { time = 0L })
        jar.write(bytes)
        jar.closeEntry()
        seen += name
    }
}
