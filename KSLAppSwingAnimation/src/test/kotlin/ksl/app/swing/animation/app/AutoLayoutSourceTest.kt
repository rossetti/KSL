package ksl.app.swing.animation.app

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.JsonLinesAnimationOutput
import ksl.animation.replay.AutoLayoutSource
import ksl.examples.general.animationbundle.Example13MovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 4: the unified `autoLayout` smart-selects its source. AUTO uses a trace only when it carries real
 * (Cartesian) coordinates; a coordinate-free (NaN) trace defers to the scaffold's faithful MDS placement,
 * and MODEL always forces the scaffold. Driven with synthetic traces so the gate is exercised directly.
 */
class AutoLayoutSourceTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Example13MovableResources.buildModel()
    }

    private fun controllerFor(tag: String): AnimationAppController {
        val ws = Files.createTempDirectory(tempRoot, "anim-autolayout-$tag")
        return AnimationAppController("Movable", builder).apply { workspaceOverride = ws }
    }

    private fun moved(name: String, fx: Double, fy: Double, tx: Double, ty: Double, from: String, to: String, t: Double) =
        AnimationEvent.SpatialElementMoved(
            simTime = t, name = name, fromX = fx, fromY = fy, toX = tx, toY = ty,
            velocity = 1.0, duration = 1.0, arrivalTime = t + 1.0, fromLocationName = from, toLocationName = to,
        )

    private fun cartesianTrace() = listOf(
        moved("M", 0.0, 0.0, 50.0, 30.0, "A", "B", 1.0),
        moved("M", 50.0, 30.0, 0.0, 0.0, "B", "A", 2.0),
    )

    private val NaN = Double.NaN
    private fun nanTrace() = listOf(moved("M", NaN, NaN, NaN, NaN, "A", "B", 1.0))

    private fun writeTrace(controller: AnimationAppController, events: List<AnimationEvent>) {
        Files.createDirectories(controller.tracesDir)
        JsonLinesAnimationOutput.toFile(controller.tracesDir.resolve("t.atf")).use { out ->
            out.writeHeader(AnimationTraceHeader())
            out.writeAll(events)
        }
    }

    @Test
    fun `AUTO uses the scaffold when there is no trace`() {
        val controller = controllerFor("none")
        assertFalse(controller.hasTrace())
        assertEquals(controller.buildScaffoldLayout(), controller.buildAutoLayout(AutoLayoutSource.AUTO))
    }

    @Test
    fun `AUTO uses the trace when it carries real coordinates, and MODEL forces the scaffold`() {
        val controller = controllerFor("cartesian")
        writeTrace(controller, cartesianTrace())
        assertTrue(controller.hasTrace())
        assertNotEquals(
            controller.buildScaffoldLayout(), controller.buildAutoLayout(AutoLayoutSource.AUTO),
            "a Cartesian trace should be used, not the scaffold",
        )
        assertEquals(
            controller.buildScaffoldLayout(), controller.buildAutoLayout(AutoLayoutSource.MODEL),
            "MODEL forces the scaffold even with a trace present",
        )
    }

    @Test
    fun `AUTO falls back to the scaffold for a coordinate-free (NaN) trace`() {
        val controller = controllerFor("nan")
        writeTrace(controller, nanTrace())
        assertTrue(controller.hasTrace())
        assertEquals(
            controller.buildScaffoldLayout(), controller.buildAutoLayout(AutoLayoutSource.AUTO),
            "a coordinate-free trace should defer to the scaffold's MDS placement",
        )
    }
}
