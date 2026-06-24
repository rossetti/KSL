package ksl.examples.general.bookbundle

import ksl.app.bundle.BundleAuthoringSession
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Test helper: assembles the 16 book-example models into a manifest bundle JAR from
 * their extracted named builders, via `BundleAuthoringSession` (the same path
 * `kslpkg assemble` uses).
 *
 * The builders JAR is written inline rather than via `KSLTestModels`'
 * `ManifestBundleFixtures` to avoid a `KSLTestModels` test dependency, which would leak
 * the appsupport ServiceLoader bundles onto KSLExamples' test classpath.
 */
internal object BookBundleFixture {

    /** The 16 named book-example builders (one per model in [BookExamplesBundle]). */
    val builders: List<Class<out ModelBuilderIfc>> = listOf(
        DriveThroughPharmacyWithResourceModelBuilder::class.java,
        DriveThroughPharmacyWithQModelBuilder::class.java,
        TandemQueueModelBuilder::class.java,
        PalletWorkCenterModelBuilder::class.java,
        StemFairMixerModelBuilder::class.java,
        TieDyeTShirtsModelBuilder::class.java,
        WalkInHealthClinicModelBuilder::class.java,
        StemFairMixerEnhancedModelBuilder::class.java,
        StemFairMixerEnhancedSchedModelBuilder::class.java,
        RQInventorySystemModelBuilder::class.java,
        TestAndRepairShopResourceConstrainedModelBuilder::class.java,
        TandemQueueWithConstrainedMovementModelBuilder::class.java,
        TandemQueueWithUnconstrainedMovementModelBuilder::class.java,
        TestAndRepairShopWithMovableResourcesModelBuilder::class.java,
        TestAndRepairShopWithConveyorModelBuilder::class.java,
        TwoEchelonInventoryModelBuilder::class.java,
    )

    /** Assembles the 16 builders into a bundle JAR at `<dir>/book-examples.jar`. */
    fun assemble(dir: Path, bundleId: String = "ksl.examples.book"): Path {
        val buildersJar = writeBuildersJar(dir, "book-builders", builders)
        val session = BundleAuthoringSession.open(buildersJar)
        session.bundleId = bundleId
        val output = dir.resolve("book-examples.jar")
        session.assemble(output, force = true)
        return output
    }

    /** Writes a classes-only builders JAR holding each builder class file + its Kotlin synthetic lambdas. */
    private fun writeBuildersJar(dir: Path, name: String, classes: List<Class<*>>): Path {
        val target = dir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            val seen = mutableSetOf<String>()
            for (cls in classes) addClassWithInnerClasses(jar, cls, seen)
        }
        return target
    }

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
