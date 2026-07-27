package ksl.examples.general.animationbundle.showcase

import ksl.app.animation.web.AnimationRunRef
import ksl.app.animation.web.SelfContainedHtmlExporter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Builds the downloadable animation pack: one playable page per bundled model, plus an index.
 *
 * This ships as its own release asset rather than inside the suite, so the install stays lean and anybody
 * who wants the animations opts in by downloading them. Each page carries its player, its trace and its
 * polished layout inside it, so it plays by double-clicking with nothing installed and nothing served —
 * which also means one of them can be sent to a student on its own.
 *
 * What goes in comes from the bundle's own manifest, paired with the layouts published beside it, so the
 * pack cannot quietly fall out of step with what the suite ships.
 */
object AnimationsPackage {

    /**
     * Models left out of the pack, with the reason. Kept as data rather than a filter buried in a loop,
     * because an exclusion that is not explained becomes an exclusion nobody dares remove.
     */
    val excluded: Map<String, String> = mapOf(
        // 80 agents stepping at a small dt write 130,000 position events: 17 MB of trace, a third of the
        // pack on its own, for one animation. The model is still in the suite; it is only the download
        // that leaves it out.
        "Example11Flocking" to "its trace alone is a third of the pack",
    )

    fun build(bundleJar: Path, tracesDir: Path, layoutsRoot: Path, outDir: Path): Result {
        val (bundleId, modelIds) = readManifest(bundleJar)
        val exporter = SelfContainedHtmlExporter.bundled()
            ?: error(
                "no packaged animation player. Build it first:\n" +
                    "  ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack"
            )

        val runs = ArrayList<AnimationRunRef>()
        val skipped = ArrayList<String>()
        for (modelId in modelIds) {
            if (modelId in excluded) continue
            val trace = tracesDir.resolve("$modelId.atf")
            val layout = layoutsRoot.resolve(bundleId).resolve("$modelId.lay.toml")
            if (!trace.exists() || !layout.exists()) {
                skipped.add(modelId)
                continue
            }
            runs.add(AnimationRunRef(title = titleOf(layout, modelId), trace = trace, layout = layout))
        }

        if (outDir.exists()) Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        val report = exporter.exportIndependent(runs, outDir, title = "KSL animations")
        return Result(runs.map { it.title }, skipped, report.totalBytes)
    }

    data class Result(val titles: List<String>, val skipped: List<String>, val totalBytes: Long)

    /** A layout's own title makes a better heading than a model id; fall back to the id when it has none. */
    private fun titleOf(layout: Path, modelId: String): String =
        runCatching { ksl.animation.AnimationLayout.read(layout).title }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: modelId

    private fun readManifest(bundleJar: Path): Pair<String, List<String>> {
        require(Files.isRegularFile(bundleJar)) { "no bundle jar at $bundleJar — assemble it first" }
        val text = ZipFile(bundleJar.toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/ksl/bundle.toml") ?: error("$bundleJar has no bundle manifest")
            zip.getInputStream(entry).bufferedReader().readText()
        }
        val bundleId = Regex("""^\s*bundleId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1) ?: error("the bundle manifest declares no bundleId")
        val modelIds = Regex("""^\s*modelId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] }.toList()
        return bundleId to modelIds
    }
}

fun main() {
    val result = AnimationsPackage.build(
        bundleJar = Path(System.getProperty("bundleJar") ?: error("-DbundleJar required")),
        tracesDir = Path(System.getProperty("traces") ?: "build/showcase"),
        layoutsRoot = Path(System.getProperty("layouts") ?: "docs/animations/layouts"),
        outDir = Path(System.getProperty("out") ?: "build/ksl-animations"),
    )
    result.titles.forEach { println("  $it") }
    AnimationsPackage.excluded.forEach { (modelId, why) -> println("  (left out: $modelId — $why)") }
    println("built ${result.titles.size} animation(s), ${result.totalBytes / 1048576} MB before compression")
    if (result.skipped.isNotEmpty()) {
        System.err.println(
            "\nNo trace or layout for: ${result.skipped.joinToString(", ")}\n" +
                "Capture what is missing, then publish the layouts:\n" +
                "  ./gradlew :KSLExamples:showcaseCapture -PmodelName=<model> -Pout=build/showcase\n" +
                "  ./gradlew :KSLExamples:publishAnimationLayouts"
        )
        kotlin.system.exitProcess(1)
    }
}
