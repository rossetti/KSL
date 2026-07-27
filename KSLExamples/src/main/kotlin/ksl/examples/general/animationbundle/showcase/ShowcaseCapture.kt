package ksl.examples.general.animationbundle.showcase

import ksl.animation.AnimationLayout
import ksl.examples.general.animationbundle.*
import ksl.animation.OverlaySpec
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.io.load
import ksl.app.session.AnimationTraceAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ksl.app.animation.replay.AutoLayoutSource
import ksl.app.animation.replay.buildAutoLayout
import ksl.simulation.Model
import java.nio.file.Files
import java.nio.file.Path

/**
 * Produces the starting material for polishing a showcase animation: one captured trace, plus the
 * **auto-layout** generated from that trace.
 *
 * The auto-layout is the starting point rather than an example's `AnimationBuilder` layout, because
 * `buildAutoLayout` is the generator the desktop app's *Auto Layout* and the MCP `auto_layout` tool both
 * delegate to — so this begins where a user actually begins, and anything achieved from here is
 * reproducible by them. It also mines the trace for real positions, flow order, mover homes and observed
 * extent, which a hand-written DSL layout cannot.
 *
 * The split into two files is what makes polishing cheap: a layout binds to a trace **by element name**,
 * so the trace is captured once and the layout can then be edited freely — no recompiling, no re-running
 * the model. That is the whole reason a polish loop is affordable.
 *
 * Usage (from the repo root):
 * ```
 * ./gradlew :KSLExamples:showcaseCapture -PmodelName=Example13MovableResources -Pout=build/showcase
 * ./gradlew :KSLExamples:showcaseCapture -PmodelName=list          # what can be captured
 * ```
 * Re-running overwrites the trace but **never** the polished layout — see [capture].
 */
object ShowcaseCapture {

    /**
     * Every model in the animation bundle, by the name passed on the command line, with the example's own
     * `AnimationBuilder` layout alongside its builder.
     *
     * All eighteen are listed rather than a curated subset. Which models are worth showcasing is a judgement
     * that changes; which models *can* be captured is a fact about the bundle, and a tool that silently
     * omits one sends whoever wants it off to edit Kotlin. The bundle's entry points are uniform
     * (`buildModel()` / `buildLayout(model)`), so listing them all costs one line each.
     */
    private val models: Map<String, ModelEntry> = mapOf(
        entry("Example01DriveThroughPharmacy", { Example01DriveThroughPharmacy.buildModel() }, { Example01DriveThroughPharmacy.buildLayout(it) }),
        entry("Example02MovingParts", { Example02MovingParts.buildModel() }, { Example02MovingParts.buildLayout(it) }),
        entry("Example03GridEpidemic", { Example03GridEpidemic.buildModel() }, { Example03GridEpidemic.buildLayout(it) }),
        entry("Example04BuildingEvacuation", { Example04BuildingEvacuation.buildModel() }, { Example04BuildingEvacuation.buildLayout(it) }),
        entry("Example05PedestrianCrowd", { Example05PedestrianCrowd.buildModel() }, { Example05PedestrianCrowd.buildLayout(it) }),
        entry("Example06WarehouseAGV", { Example06WarehouseAGV.buildModel() }, { Example06WarehouseAGV.buildLayout(it) }),
        entry("Example07StationTandem", { Example07StationTandem.buildModel() }, { Example07StationTandem.buildLayout(it) }),
        entry("Example08ConveyorTandem", { Example08ConveyorTandem.buildModel() }, { Example08ConveyorTandem.buildLayout(it) }),
        entry("Example09DistancesTandem", { Example09DistancesTandem.buildModel() }, { Example09DistancesTandem.buildLayout(it) }),
        entry("Example10MultiClassStation", { Example10MultiClassStation.buildModel() }, { Example10MultiClassStation.buildLayout(it) }),
        entry("Example11Flocking", { Example11Flocking.buildModel() }, { Example11Flocking.buildLayout(it) }),
        entry("Example12StemFairStorage", { Example12StemFairStorage.buildModel() }, { Example12StemFairStorage.buildLayout(it) }),
        entry("Example13MovableResources", { Example13MovableResources.buildModel() }, { Example13MovableResources.buildLayout(it) }),
        entry("Example14AnnotatedClinic", { Example14AnnotatedClinic.buildModel() }, { Example14AnnotatedClinic.buildLayout(it) }),
        entry("Example15DroneDelivery", { Example15DroneDelivery.buildModel() }, { Example15DroneDelivery.buildLayout(it) }),
        entry("Example16NetworkRumor", { Example16NetworkRumor.buildModel() }, { Example16NetworkRumor.buildLayout(it) }),
        entry("Example17TandemBlocking", { Example17TandemBlocking.buildModel() }, { Example17TandemBlocking.buildLayout(it) }),
        entry("Example18ConveyorTestRepair", { Example18ConveyorTestRepair.buildModel() }, { Example18ConveyorTestRepair.buildLayout(it) }),
    )

    /** A capturable model: how to build it, and how to build the example's own hand-written layout. */
    private class ModelEntry(val build: () -> Model, val dslLayout: (Model) -> AnimationLayout)

    private fun entry(name: String, build: () -> Model, layout: (Model) -> AnimationLayout) =
        name to ModelEntry(build, layout)

    /** The capturable model names, for an error message or a `-PmodelName=list`. */
    val modelNames: List<String> get() = models.keys.sorted()

    /**
     * Captures `<name>.atf` and, unless it already exists, `<name>.lay.json` into [outDir].
     *
     * The layout is written only when absent. Once polishing has started, that file is the work — and a
     * re-capture (to shorten a run, say) must not silently discard it. Delete it deliberately to start the
     * layout over.
     */
    fun capture(name: String, outDir: Path, overlays: OverlaySpec = EVERY_OVERLAY): Result {
        val model = models[name] ?: error(
            "unknown model '$name'\nknown models:\n" + modelNames.joinToString("\n") { "  $it" }
        )
        val build = model.build
        Files.createDirectories(outDir)
        val traceFile = outDir.resolve("$name.atf")
        val layoutFile = outDir.resolve("$name.lay.json")

        // Drive the capture attachment's lifecycle directly -- the same hooks a run orchestrator calls. A
        // layout is required to capture but is irrelevant to the trace (the trace records what happened, not
        // how to draw it), so capture against an empty one and derive the real layout afterwards.
        val attachment = AnimationTraceAttachment.replay(
            traceFile = traceFile, layout = AnimationLayout(), layoutFile = outDir.resolve("$name.capture.lay.json"),
            overlays = overlays
        )
        val running = build()
        attachment.onAttach(running, CoroutineScope(SupervisorJob()))
        try {
            running.simulate()
        } finally {
            attachment.onDetach()
        }
        Files.deleteIfExists(outDir.resolve("$name.capture.lay.json"))

        // The example's own DSL layout, written under a distinct name so it never competes with the layout
        // being polished. It is worth having beside the generated one: the DSL layout carries the author's
        // intent (which elements matter, what they are called), while the auto-layout carries what the run
        // actually did. Polishing usually wants both.
        val dslLayoutFile = outDir.resolve("$name.dsl.lay.json")
        val dslLayoutWritten = runCatching {
            model.dslLayout(build()).writeToFile(dslLayoutFile)
        }.isSuccess

        val existed = Files.exists(layoutFile) && Files.size(layoutFile) > 200
        if (!existed) {
            // Regenerate the model: buildAutoLayout probes the built model's structure, and the instance
            // used for the run has already been simulated.
            val source = AnimationSource.load(null, traceFile)
            val auto = build().buildAutoLayout(source, AutoLayoutSource.AUTO)
            auto.writeToFile(layoutFile)
        }
        return Result(traceFile, layoutFile, layoutWasKept = existed, dslLayoutFile.takeIf { dslLayoutWritten })
    }

    data class Result(
        val traceFile: Path,
        val layoutFile: Path,
        val layoutWasKept: Boolean,
        /** The example's own `AnimationBuilder` layout, or null if the example could not produce one. */
        val dslLayoutFile: Path?
    )

    /**
     * Every overlay on. A showcase should capture everything a model can express, because deciding which
     * overlays help is a *display* choice made later while polishing -- and re-capturing to add one back
     * would invalidate whatever polishing had already been done against the old trace.
     */
    private val EVERY_OVERLAY = OverlaySpec(
        velocities = true, forces = true, flowField = true, plannedPaths = true, markerPulses = true
    )
}

fun main() {
    val name = System.getProperty("modelName") ?: error(
        "-PmodelName=<ExampleNN…> is required (or -PmodelName=list to see what is available)"
    )
    if (name.equals("list", ignoreCase = true)) {
        println(ShowcaseCapture.modelNames.joinToString("\n"))
        return
    }
    val outDir = Path.of(System.getProperty("out") ?: "build/showcase")
    val result = ShowcaseCapture.capture(name, outDir)
    val events = Files.readAllLines(result.traceFile).size - 1
    println("trace   ${result.traceFile}  ($events events, ${Files.size(result.traceFile) / 1024} KB)")
    println(
        if (result.layoutWasKept) "layout  ${result.layoutFile}  (KEPT - polishing in progress)"
        else "layout  ${result.layoutFile}  (auto-generated starting point)"
    )
    result.dslLayoutFile?.let { println("dsl     $it  (the example's own layout, for comparison)") }
}
