package ksl.examples.general.vehiclebundle

import ksl.animation.AnimationLayout
import ksl.animation.animationInventory
import ksl.animation.validateAgainst
import ksl.app.settings.WorkspaceLayout
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the layouts the suite ships for the vehicle models, as `ShippedLayoutTest` does for the animation
 * models: one per model in the assembled bundle and none for a model that is gone, each binding to names the
 * model has, each styling every guide path the model has, and each found by the lookup the Animation app uses.
 *
 * The layouts start as `scaffoldVehicleLayouts` output and are polished by hand; this is what keeps a polished
 * layout honest when a model is renamed or a guide path is added.
 */
class VehicleShippedLayoutTest {

    private val bundleJar: Path = Path.of("build/libs/vehicle-examples.jar")
    private val layoutRoot: Path = Path.of("../docs/animations/layouts")

    /** The bundle's id and its models, with their builder classes, read from the assembled jar's manifest. */
    private fun manifest(): Pair<String, List<Pair<String, String>>> {
        assertTrue(Files.isRegularFile(bundleJar), "no $bundleJar — run :KSLExamples:vehicleExamplesBundleJar")
        val text = ZipFile(bundleJar.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/ksl/bundle.toml")).bufferedReader().readText()
        }
        val bundleId = Regex("""^\s*bundleId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(text)!!.groupValues[1]
        val models = Regex("""^\s*modelId\s*=\s*"([^"]+)"\s*$\s*builderClass\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return bundleId to models
    }

    private fun builder(className: String) =
        Class.forName(className).getDeclaredConstructor().newInstance() as ModelBuilderIfc

    @Test
    @DisplayName("every vehicle model the bundle ships has a layout, and every layout has a model")
    fun shippedLayoutsMatchTheBundlesModels() {
        val (bundleId, models) = manifest()
        assertEquals(10, models.size, "the vehicle bundle ships ten models")
        val dir = layoutRoot.resolve(bundleId)
        assertTrue(Files.isDirectory(dir), "no shipped layouts at $dir — run :KSLExamples:scaffoldVehicleLayouts")
        val present = Files.list(dir).use { paths ->
            paths.map { it.name }.filter { it.endsWith(".lay.toml") }.map { it.removeSuffix(".lay.toml") }
                .toList().toSortedSet()
        }
        assertEquals(models.map { it.first }.toSortedSet(), present, "shipped layouts must correspond to the bundle's models")
    }

    @Test
    @DisplayName("every shipped vehicle layout loads, binds to its model, and styles each of its guide paths")
    fun shippedLayoutsBindToTheirModels() {
        val (bundleId, models) = manifest()
        val problems = StringBuilder()
        for ((modelId, builderClass) in models) {
            val file = layoutRoot.resolve(bundleId).resolve("$modelId.lay.toml")
            if (!Files.isRegularFile(file)) continue // the other test reports this
            val layout = runCatching { AnimationLayout.read(file) }
                .getOrElse { problems.appendLine("$modelId: cannot be read — $it"); continue }
            val model = builder(builderClass).build(null, null as ExperimentRunParametersIfc?)
            val report = layout.validateAgainst(model)
            if (!report.isValid) problems.append("$modelId:\n").append(report).append('\n')
            // A guide path with no entry draws with the defaults, which on a path that climbs means floors on
            // top of each other; a shipped layout says where each one goes.
            val paths = model.animationInventory().guidedPaths.map { it.spaceName }.toSet()
            val styled = layout.guidedPaths.map { it.spaceName }.toSet()
            if (paths != styled) problems.appendLine("$modelId: guide paths $paths, styled $styled")
        }
        assertTrue(problems.isEmpty(), "Shipped vehicle layouts with problems:\n$problems")
    }

    @Test
    @DisplayName("the vehicle models featured in the pack and gallery are shipped models")
    fun theFeaturedVehiclesAreShipped() {
        val (bundleId, models) = manifest()
        val featured = ksl.examples.general.animationbundle.showcase.FeaturedVehicles
        assertEquals(bundleId, featured.BUNDLE_ID)
        val shipped = models.map { it.first }.toSet()
        assertTrue(shipped.containsAll(featured.modelIds), "featured ${featured.modelIds}, shipped $shipped")
    }

    @Test
    @DisplayName("the app finds a shipped vehicle layout by bundle and model id")
    fun theLookupTheAppUsesResolves() {
        val (bundleId, models) = manifest()
        val property = WorkspaceLayout.BUILTIN_LAYOUTS_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, layoutRoot.toAbsolutePath().toString())
            for ((modelId, _) in models) {
                assertTrue(WorkspaceLayout.builtinLayoutFor(bundleId, modelId) != null, "the app's lookup must find $modelId")
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
