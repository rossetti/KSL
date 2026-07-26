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
 * ./gradlew :KSLAppSwingAnimation:showcaseCapture -PmodelName=Example13MovableResources -Pout=build/showcase
 * ```
 * Re-running overwrites the trace but **never** the polished layout — see [capture].
 */
object ShowcaseCapture {

    /** The models eligible for showcasing, by the name passed on the command line. */
    private val models: Map<String, () -> Model> = mapOf(
        "Example01DriveThroughPharmacy" to { Example01DriveThroughPharmacy.buildModel() },
        "Example02MovingParts" to { Example02MovingParts.buildModel() },
        "Example03GridEpidemic" to { Example03GridEpidemic.buildModel() },
        "Example04BuildingEvacuation" to { Example04BuildingEvacuation.buildModel() },
        "Example05PedestrianCrowd" to { Example05PedestrianCrowd.buildModel() },
        "Example06WarehouseAGV" to { Example06WarehouseAGV.buildModel() },
        "Example08ConveyorTandem" to { Example08ConveyorTandem.buildModel() },
        "Example09DistancesTandem" to { Example09DistancesTandem.buildModel() },
        "Example11Flocking" to { Example11Flocking.buildModel() },
        "Example12StemFairStorage" to { Example12StemFairStorage.buildModel() },
        "Example13MovableResources" to { Example13MovableResources.buildModel() },
        "Example15DroneDelivery" to { Example15DroneDelivery.buildModel() },
    )

    /**
     * Captures `<name>.atf` and, unless it already exists, `<name>.lay.json` into [outDir].
     *
     * The layout is written only when absent. Once polishing has started, that file is the work — and a
     * re-capture (to shorten a run, say) must not silently discard it. Delete it deliberately to start the
     * layout over.
     */
    fun capture(name: String, outDir: Path, overlays: OverlaySpec = EVERY_OVERLAY): Result {
        val build = models[name] ?: error("unknown model '$name'; known: ${models.keys.sorted()}")
        Files.createDirectories(outDir)
        val traceFile = outDir.resolve("$name.atf")
        val layoutFile = outDir.resolve("$name.lay.json")

        // A layout is required to capture, but which one is irrelevant to the trace -- the trace records
        // what happened, not how to draw it. So capture against an empty one and derive the real layout
        // from the trace afterwards.
        // Drive the capture attachment's lifecycle directly -- the same hooks a run orchestrator calls.
        // A layout is required to capture but is irrelevant to the trace, so an empty one is used and the
        // real layout is derived from the trace afterwards.
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

        // The example's own DSL layout, for comparison against the generated one. Written under a
        // distinct name so it never competes with the layout being polished.
        runCatching {
            val m = build()
            val dsl = Example13MovableResources::class.java // presence check only; per-model below
            when (name) {
                "Example13MovableResources" -> Example13MovableResources.buildLayout(m)
                else -> null
            }?.writeToFile(outDir.resolve("$name.dsl.lay.json"))
        }

        val existed = Files.exists(layoutFile) && Files.size(layoutFile) > 200
        if (!existed) {
            // Regenerate the model: buildAutoLayout probes the built model's structure, and the instance
            // used for the run has already been simulated.
            val source = AnimationSource.load(null, traceFile)
            val auto = build().buildAutoLayout(source, AutoLayoutSource.AUTO)
            auto.writeToFile(layoutFile)
        }
        return Result(traceFile, layoutFile, layoutWasKept = existed)
    }

    data class Result(val traceFile: Path, val layoutFile: Path, val layoutWasKept: Boolean)

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
    val name = System.getProperty("modelName") ?: error("-PmodelName=<ExampleNN…> is required")
    val outDir = Path.of(System.getProperty("out") ?: "build/showcase")
    val result = ShowcaseCapture.capture(name, outDir)
    val events = Files.readAllLines(result.traceFile).size - 1
    println("trace   ${result.traceFile}  ($events events, ${Files.size(result.traceFile) / 1024} KB)")
    println(
        if (result.layoutWasKept) "layout  ${result.layoutFile}  (KEPT - polishing in progress)"
        else "layout  ${result.layoutFile}  (auto-generated starting point)"
    )
}
