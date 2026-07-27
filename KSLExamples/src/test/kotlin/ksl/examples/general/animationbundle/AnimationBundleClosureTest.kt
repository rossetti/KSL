package ksl.examples.general.animationbundle

import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.assertTrue

/**
 * Guards the animation bundle against shipping a model it cannot build.
 *
 * The bundle jar is assembled from **path patterns** in `KSLExamples/build.gradle.kts`, not from the call
 * graph, so a builder whose model class is not matched by one of those patterns packages perfectly and then
 * fails on the user's machine with a `NoClassDefFoundError`. Nothing at compile time notices. Neither does
 * any ordinary test: the test JVM has the whole project on its classpath, so it finds the class the jar is
 * missing. Two builders shipped in exactly that state before this test existed.
 *
 * So the test loads each builder **out of the jar** through a class loader that refuses to fall back to the
 * project's own classes for anything under `ksl.examples`, which is the situation a user is in — KSL on the
 * classpath, and the bundle supplying the models. If a class was left out of the jar, building fails here
 * the same way it would there.
 *
 * Checking the artifact is what makes this worth having. An earlier attempt walked the built model's element
 * tree and compared the classes against the include patterns parsed out of the build file; it needed
 * reflection into a protected field, re-implemented Ant globbing to do the comparison, and still only tested
 * a *model* of the packaging rather than the package.
 */
class AnimationBundleClosureTest {

    /** Every builder the bundle ships, by class name — mirroring `AnimationExampleBuilders`. */
    private val bundled = listOf(
        "Example01DriveThroughPharmacyBuilder",
        "Example02MovingPartsBuilder",
        "Example03GridEpidemicBuilder",
        "Example04BuildingEvacuationBuilder",
        "Example05PedestrianCrowdBuilder",
        "Example06WarehouseAGVBuilder",
        "Example08ConveyorTandemBuilder",
        "Example09DistancesTandemBuilder",
        "Example11FlockingBuilder",
        "Example12StemFairStorageBuilder",
        "Example13MovableResourcesBuilder",
        "Example14AnnotatedClinicBuilder",
        "Example15DroneDeliveryBuilder",
        "Example17TandemBlockingBuilder",
        "Example18ConveyorTestRepairBuilder",
    )

    @Test
    @DisplayName("every bundled model builds from the jar alone")
    fun everyBundledModelBuildsFromTheJar() {
        val jar = File(System.getProperty("animationBundleJar") ?: "")
        assumeTrue(jar.isFile, "no bundle jar; run :KSLExamples:animationBuildersJar")

        val failures = LinkedHashMap<String, String>()
        BundleOnlyClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { loader ->
            for (name in bundled) {
                val qualified = "ksl.examples.general.animationbundle.$name"
                runCatching {
                    val builder = loader.loadClass(qualified).getDeclaredConstructor().newInstance()
                    (builder as ModelBuilderIfc).build(null, null)
                }.onFailure { failures[name] = "${it::class.simpleName}: ${it.message}" }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("These bundled examples cannot be built from the animation-examples jar, so they")
                appendLine("would fail for a user. Usually the model class is not matched by any include")
                appendLine("pattern of the animationBuildersJar task in KSLExamples/build.gradle.kts:")
                failures.forEach { (name, why) -> appendLine("  $name — $why") }
            }
        )
    }

    /**
     * Loads anything under `ksl.examples` from the bundle jar and nothing else from anywhere else.
     *
     * Delegating to the parent first — the normal order — would find the project's own compiled classes and
     * the test would pass whether or not the jar contained them. Everything outside `ksl.examples` still
     * comes from the parent, because that is what a user has: KSL itself, plus a bundle.
     */
    private class BundleOnlyClassLoader(jar: URL, parent: ClassLoader) : URLClassLoader(arrayOf(jar), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("ksl.examples.")) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                return findClass(name).also { if (resolve) resolveClass(it) }
            }
        }
    }
}
