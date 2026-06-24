package ksl.examples.general.bookbundle

import ksl.app.bundle.BundleAuthoringSession
import ksl.app.bundle.BundleLoader
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the 16 book-example models assemble + load through the manifest mechanism
 * from their extracted named builders — the unblock for eventually retiring the
 * `BookExamplesBundle` ServiceLoader registration. Each model's descriptor is extracted
 * by building it once during assembly, so a clean load is end-to-end proof.
 *
 * (The builders JAR is written inline rather than via `ManifestBundleFixtures` to avoid
 * a `KSLTestModels` test dependency, which would leak the appsupport ServiceLoader
 * bundles onto KSLExamples' test classpath.)
 */
class BookExamplesManifestAssemblyTest {

    private val builders: List<Class<out ModelBuilderIfc>> = listOf(
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

    @Test
    fun `the 16 book builders assemble and load as one manifest bundle`(@TempDir dir: Path) {
        val buildersJar = writeBuildersJar(dir, "book-builders", builders)

        val session = BundleAuthoringSession.open(buildersJar)
        session.bundleId = "ksl.examples.book"
        val bundle = dir.resolve("book-examples.jar")
        session.assemble(bundle, force = true)

        BundleLoader.loadJar(bundle).single().use { lb ->
            assertEquals("ksl.examples.book", lb.bundle.bundleId)
            assertEquals(16, lb.bundle.models.size, "all 16 book models should assemble")
            // Spot-check a few descriptors resolve (each model is built once to extract it).
            for (id in listOf("TandemQueue", "RQInventorySystem", "TwoEchelonInventory")) {
                assertTrue(lb.descriptorFor(id).responseNames.isNotEmpty(), "descriptor for $id should resolve")
            }
        }
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
