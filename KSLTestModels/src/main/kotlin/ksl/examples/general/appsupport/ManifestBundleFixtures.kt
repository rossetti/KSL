/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.appsupport

import ksl.app.bundle.BundleAuthoringSession
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Test/fixtures helper: assembles a real **manifest bundle JAR** from one or more named
 * [ModelBuilderIfc] classes, exactly the way `kslpkg assemble` and the Bundle Workbench
 * do — via `ksl.app.bundle.BundleAuthoringSession`.
 *
 * It lives in KSLTestModels' main source set so any module that
 * `testImplementation`s KSLTestModels can build a manifest bundle on demand. This is
 * the seam for migrating tests off the in-process `ServiceLoader`/classpath bundles
 * onto JAR-loaded `ManifestBackedBundle`s.
 */
object ManifestBundleFixtures {

    /**
     * Builds a plain *builders JAR* containing the class files of [builders] (their
     * dependencies — KSLCore types, model classes — resolve through the parent
     * classloader, as in production), opens a [BundleAuthoringSession] over it, applies
     * [bundleId] and any per-model edits via [configure], and assembles a manifest
     * bundle JAR at `<dir>/<jarName>.jar`.
     *
     * The per-model `modelId` and `supportedApps` follow the session defaults unless
     * [configure] overrides them (e.g. setting a model's `supportedApps` to SIMOPT-only).
     *
     * @return the path to the assembled bundle JAR
     */
    fun assembleManifestBundle(
        dir: Path,
        jarName: String,
        bundleId: String,
        vararg builders: Class<out ModelBuilderIfc>,
        configure: (BundleAuthoringSession) -> Unit = {},
    ): Path {
        require(builders.isNotEmpty()) { "at least one builder class is required" }
        val buildersJar = writeBuildersJar(dir, "$jarName-builders", builders.toList())
        val session = BundleAuthoringSession.open(buildersJar)
        session.bundleId = bundleId
        configure(session)
        val output = dir.resolve("$jarName.jar")
        session.assemble(output, force = true)
        return output
    }

    /** Writes a plain *builders JAR* holding the class files of [builders] (their dependencies resolve
     *  through the parent classloader, as in production) — the un-assembled input to the authoring flow
     *  (`BundleAuthoringSession.open` / the server's bundle-authoring tools). */
    fun buildersJar(dir: Path, name: String, vararg builders: Class<out ModelBuilderIfc>): Path {
        require(builders.isNotEmpty()) { "at least one builder class is required" }
        return writeBuildersJar(dir, name, builders.toList())
    }

    /** Writes a classes-only JAR (no manifest, no services) holding each builder's class file. */
    private fun writeBuildersJar(dir: Path, name: String, classes: List<Class<*>>): Path {
        val target = dir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            val seen = mutableSetOf<String>()
            for (cls in classes) addClassWithInnerClasses(jar, cls, seen)
        }
        return target
    }

    /** Adds [cls] plus any sibling `<simpleName>$*.class` files (Kotlin synthetic lambdas). */
    private fun addClassWithInnerClasses(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        addClass(jar, cls, seen)
        val pkgPath = cls.`package`.name.replace('.', '/')
        val pkgDir = cls.classLoader.getResource(pkgPath)?.let {
            try { Paths.get(it.toURI()) } catch (_: Exception) { null }
        } ?: return
        if (!Files.isDirectory(pkgDir)) return
        Files.list(pkgDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("${cls.simpleName}\$") }
                .filter { it.fileName.toString().endsWith(".class") }
                .forEach { sibling ->
                    val entryName = "$pkgPath/${sibling.fileName}"
                    if (seen.add(entryName)) {
                        jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
                        jar.write(Files.readAllBytes(sibling))
                        jar.closeEntry()
                    }
                }
        }
    }

    private fun addClass(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        val entryName = cls.name.replace('.', '/') + ".class"
        if (!seen.add(entryName)) return
        val bytes = cls.classLoader.getResourceAsStream(entryName)?.use { it.readBytes() }
            ?: error("Cannot locate class file for ${cls.name} on the classpath")
        jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
        jar.write(bytes)
        jar.closeEntry()
    }
}
