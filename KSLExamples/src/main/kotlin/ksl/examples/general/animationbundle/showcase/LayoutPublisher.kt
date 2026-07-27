package ksl.examples.general.animationbundle.showcase

import ksl.animation.AnimationLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Turns the polish scripts' output into the layouts that ship with the suite.
 *
 * A layout is only meaningful for one model in one bundle, so the shipped ones are keyed that way —
 * `<bundleId>/<modelId>.lay.toml` — which is exactly the pair the animation app holds when a model is open.
 * Finding the layout for what a student is looking at is then a path lookup rather than a search, and two
 * bundles can carry a model of the same name without colliding.
 *
 * The bundle's own manifest is the authority for what needs one. Reading the ids out of the assembled jar
 * rather than restating them here means a model added to the bundle fails this step until it has a layout,
 * instead of quietly shipping without one.
 *
 * TOML, not JSON, because `.lay.toml` is what the animation app writes when a student saves a layout: ours
 * and theirs should be the same kind of file, and it is the readable one to open in an editor. The
 * conversion goes through `AnimationLayout` itself, so the shipped form is by construction something the
 * app can read back.
 */
object LayoutPublisher {

    fun publish(bundleJar: Path, polishedDir: Path, outputRoot: Path): Result {
        val (bundleId, modelIds) = readManifest(bundleJar)
        val target = outputRoot.resolve(bundleId).also { it.createDirectories() }

        val written = ArrayList<Path>()
        val missing = ArrayList<String>()
        for (modelId in modelIds) {
            val source = polishedDir.resolve("$modelId.lay.json")
            if (!source.exists()) {
                missing.add(modelId)
                continue
            }
            val out = target.resolve("$modelId.lay.toml")
            AnimationLayout.read(source).writeTomlToFile(out)
            written.add(out)
        }

        // A stale layout for a model the bundle no longer ships is worse than none: it would be offered for
        // something that is not there, or quietly kept alive after the model was dropped.
        val expected = modelIds.map { "$it.lay.toml" }.toSet()
        val stale = Files.list(target).use { paths ->
            paths.filter { it.name.endsWith(".lay.toml") && it.name !in expected }.toList()
        }
        stale.forEach(Files::delete)

        return Result(bundleId, written, missing, stale)
    }

    data class Result(
        val bundleId: String,
        val written: List<Path>,
        /** Models the bundle ships that have no polished layout — a failure, not a warning. */
        val missing: List<String>,
        val removed: List<Path>
    )

    /** The bundle's id and the ids of the models it ships, from the manifest inside the assembled jar. */
    private fun readManifest(bundleJar: Path): Pair<String, List<String>> {
        require(Files.isRegularFile(bundleJar)) { "no bundle jar at $bundleJar — assemble it first" }
        val manifest = ZipFile(bundleJar.toFile()).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: error("$bundleJar has no $MANIFEST_ENTRY")
            zip.getInputStream(entry).bufferedReader().readText()
        }
        val bundleId = Regex("""^\s*bundleId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(manifest)?.groupValues?.get(1)
            ?: error("$MANIFEST_ENTRY declares no bundleId")
        val modelIds = Regex("""^\s*modelId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(manifest).map { it.groupValues[1] }.toList()
        require(modelIds.isNotEmpty()) { "$MANIFEST_ENTRY declares no models" }
        return bundleId to modelIds
    }

    private const val MANIFEST_ENTRY = "META-INF/ksl/bundle.toml"
}

fun main() {
    val bundleJar = Path(System.getProperty("bundleJar") ?: error("-DbundleJar required"))
    val polished = Path(System.getProperty("polished") ?: "build/showcase/polished")
    val out = Path(System.getProperty("out") ?: "docs/animations/layouts")

    val result = LayoutPublisher.publish(bundleJar, polished, out)
    result.written.forEach { println("  ${result.bundleId}/${it.name}") }
    result.removed.forEach { println("  removed stale ${it.name}") }
    println("published ${result.written.size} layout(s) for ${result.bundleId}")
    if (result.missing.isNotEmpty()) {
        System.err.println(
            "\nNo polished layout for: ${result.missing.joinToString(", ")}\n" +
                "Every model the bundle ships needs one. Produce it with:\n" +
                "  ./gradlew :KSLExamples:showcaseCapture -PmodelName=<model> -Pout=build/showcase\n" +
                "  python3 docs/animations/polish-<model>.py"
        )
        kotlin.system.exitProcess(1)
    }
}
