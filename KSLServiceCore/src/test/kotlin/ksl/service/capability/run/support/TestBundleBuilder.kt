/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.service.capability.run.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Builds a real bundle JAR by copying a `KSLModelBundle` class (and its inner
 * classes) off the running test's classpath and emitting a
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` registration. (Mirrors the
 * fixture in `KSLBundleTools` tests.) KSLCore classes resolve through the test
 * classpath via parent delegation, so the jar need not embed them.
 */
internal object TestBundleBuilder {

    fun build(
        dir: Path,
        name: String,
        bundleClasses: List<Class<*>>,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): Path {
        val target = dir.resolve("$name.jar")
        Files.newOutputStream(target).use { os ->
            JarOutputStream(os, Manifest()).use { jar ->
                val seen = mutableSetOf<String>()
                for (cls in bundleClasses) addClassWithInnerClasses(jar, cls, seen)
                val servicesPath = "META-INF/services/ksl.app.bundle.KSLModelBundle"
                if (servicesPath !in seen) {
                    jar.putNextEntry(JarEntry(servicesPath).apply { time = 0L })
                    jar.write((bundleClasses.joinToString("\n") { it.name } + "\n").toByteArray(Charsets.UTF_8))
                    jar.closeEntry()
                    seen += servicesPath
                }
                for ((entryName, bytes) in extraEntries) {
                    if (entryName in seen) continue
                    jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
                    jar.write(bytes)
                    jar.closeEntry()
                    seen += entryName
                }
            }
        }
        return target
    }

    private fun addClassWithInnerClasses(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        addClass(jar, cls, seen)
        val pkgPath = cls.`package`.name.replace('.', '/')
        val pkgDir = cls.classLoader.getResource(pkgPath)?.let {
            runCatching { java.nio.file.Paths.get(it.toURI()) }.getOrNull()
        } ?: return
        if (!Files.isDirectory(pkgDir)) return
        Files.list(pkgDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("${cls.simpleName}\$") }
                .filter { it.fileName.toString().endsWith(".class") }
                .forEach { sibling ->
                    val entryName = "$pkgPath/${sibling.fileName}"
                    if (entryName !in seen) {
                        jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
                        jar.write(Files.readAllBytes(sibling))
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
        jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
        jar.write(bytes)
        jar.closeEntry()
        seen += entryName
    }
}
