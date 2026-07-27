package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.validateAgainst
import ksl.app.settings.WorkspaceLayout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the layouts the suite ships against drifting from the models they belong to.
 *
 * A layout names a model's queues, resources and locations, so it is meaningful for exactly one model in
 * one bundle and useless — or worse, misleading — for any other. The shipped ones are keyed by that pair,
 * `<bundleId>/<modelId>.lay.toml`, which is also how the animation app finds the one for a model a student
 * has open. That correspondence is worth asserting, because both halves can move independently: a model can
 * be added to the bundle, or renamed, without anyone touching the layouts folder.
 */
class ShippedLayoutTest {

    private val bundleJar: Path = Path.of("build/libs/animation-examples.jar")
    private val layoutRoot: Path = Path.of("../docs/animations/layouts")

    /** The bundle's id and the model ids it declares, read from the manifest inside the assembled jar. */
    private fun manifest(): Pair<String, List<String>> {
        assertTrue(Files.isRegularFile(bundleJar), "no $bundleJar — run :KSLExamples:animationExamplesBundleJar")
        val text = ZipFile(bundleJar.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/ksl/bundle.toml")).bufferedReader().readText()
        }
        val bundleId = Regex("""^\s*bundleId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(text)!!.groupValues[1]
        val modelIds = Regex("""^\s*modelId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] }.toList()
        return bundleId to modelIds
    }

    @Test
    @DisplayName("every model the bundle ships has a layout, and every layout has a model")
    fun shippedLayoutsMatchTheBundlesModels() {
        val (bundleId, modelIds) = manifest()
        val dir = layoutRoot.resolve(bundleId)
        assertTrue(Files.isDirectory(dir), "no shipped layouts at $dir — run :KSLExamples:publishAnimationLayouts")

        val present = Files.list(dir).use { paths ->
            paths.map { it.name }.filter { it.endsWith(".lay.toml") }.map { it.removeSuffix(".lay.toml") }
                .toList().toSortedSet()
        }
        // Both directions. A missing layout leaves a shipped model with nothing to offer; an extra one is a
        // layout for a model that is no longer there, which would be offered for something that cannot use it.
        assertEquals(modelIds.toSortedSet(), present, "shipped layouts must correspond to the bundle's models")
    }

    @Test
    @DisplayName("every shipped layout loads and names things the model actually has")
    fun shippedLayoutsBindToTheirModels() {
        val (bundleId, modelIds) = manifest()
        val problems = StringBuilder()
        for (modelId in modelIds) {
            val file = layoutRoot.resolve(bundleId).resolve("$modelId.lay.toml")
            if (!Files.isRegularFile(file)) continue // the other test reports this
            val layout = runCatching { AnimationLayout.read(file) }
                .getOrElse { problems.appendLine("$modelId: cannot be read — $it"); continue }
            // Validation is what catches a layout left behind by a renamed queue or resource: the element is
            // still drawn, just never bound to anything, so nothing moves and nothing says why.
            val report = layout.validateAgainst(builderFor(modelId).build(null, null))
            if (!report.isValid) problems.append("$modelId:\n").append(report).append('\n')
        }
        assertTrue(problems.isEmpty(), "Shipped layouts with unbound names:\n$problems")
    }

    @Test
    @DisplayName("the app finds a shipped layout by bundle and model id")
    fun theLookupTheAppUsesResolves() {
        val (bundleId, modelIds) = manifest()
        val property = WorkspaceLayout.BUILTIN_LAYOUTS_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, layoutRoot.toAbsolutePath().toString())
            // Exactly the call the animation app makes when a model is open.
            val found = WorkspaceLayout.builtinLayoutFor(bundleId, modelIds.first())
            assertTrue(found != null, "the app's own lookup must find ${modelIds.first()}")
            assertTrue(WorkspaceLayout.builtinLayoutFor(bundleId, "NoSuchModel") == null, "and only when it exists")
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private fun builderFor(modelId: String) =
        Class.forName("ksl.examples.general.animationbundle.${modelId}Builder")
            .getDeclaredConstructor().newInstance() as ksl.simulation.ModelBuilderIfc
}
