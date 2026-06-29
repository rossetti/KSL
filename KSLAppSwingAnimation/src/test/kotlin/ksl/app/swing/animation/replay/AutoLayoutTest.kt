package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the fallback used by "Open trace…": a trace with no layout is given a grid layout derived from
 * its own elements ([ReplayModel.autoLayout]), so the replay renders instead of showing a blank canvas.
 */
class AutoLayoutTest {

    private val events = listOf(
        AnimationEvent.ReplicationStarted(0.0, 1),
        AnimationEvent.QueueLengthChanged(0.0, "WaitQ", 2),
        AnimationEvent.ResourceStateChanged(0.0, "Server", "Server_Busy", busyUnits = 1, capacity = 1),
        AnimationEvent.ResponseObserved(1.0, "NumInSystem", 3.0),
        AnimationEvent.QueueLengthChanged(2.0, "WaitQ", 0),
        AnimationEvent.ResourceStateChanged(2.0, "Server", "Server_Idle", busyUnits = 0, capacity = 1)
    )

    private fun model(layout: ksl.animation.AnimationLayout?) =
        ReplayModel.build(AnimationSource(layout = layout, header = AnimationTraceHeader(), events = events))

    @Test
    fun `auto layout places the trace's resources and queues plus a clock`() {
        val layout = model(null).autoLayout(events, "My Trace")
        assertEquals("My Trace", layout.title)
        assertContains(layout.resources.map { it.resourceName }, "Server")
        assertContains(layout.queues.map { it.queueName }, "WaitQ")
        assertTrue(layout.clocks.isNotEmpty(), "a clock is always placed")
        // Response stats are intentionally omitted to avoid a tall, overlapping auto-grid.
        assertTrue(layout.values.isEmpty(), "responses are not auto-placed")
    }

    @Test
    fun `a layout-less source renders blank but the auto layout renders content`() {
        val blank = paintedPixels(model(null))
        val populated = paintedPixels(model(model(null).autoLayout(events)))
        assertTrue(populated > 500, "auto layout draws content, got $populated px")
        assertTrue(populated > blank * 5, "auto layout draws far more than the layout-less render ($populated vs $blank)")
    }

    @Test
    fun `a spatial trace frames the grid space and assigns agent state colors`() {
        // A grid space + two agents that move and report states (no resources/queues): the classic agent case
        // that Quick view used to render as a tiny corner blob with no coloring (P5).
        val spatial = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.SpaceDefined(0.0, "grid", "Grid", cols = 20, rows = 20, cellSize = 1.0),
            AnimationEvent.AgentPositionChanged(0.0, "a1", "grid", 1.0, 1.0),
            AnimationEvent.AgentStateEntered(0.0, "a1", "Susceptible"),
            AnimationEvent.AgentPositionChanged(1.0, "a1", "grid", 5.0, 8.0),
            AnimationEvent.AgentStateEntered(1.0, "a1", "Infected"),
            AnimationEvent.AgentPositionChanged(0.0, "a2", "grid", 18.0, 17.0),
            AnimationEvent.AgentStateEntered(0.0, "a2", "Recovered")
        )
        val probe = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = spatial))
        val layout = probe.autoLayout(spatial)
        // The grid space is carried over and the canvas is framed to its 20x20 extent (not a default 800x700).
        assertTrue(layout.spaces.isNotEmpty(), "the derived grid space is included")
        assertTrue(layout.width <= 60.0 && layout.height <= 60.0, "canvas is framed to the small grid, got ${layout.width}x${layout.height}")
        // Agent state colors are assigned from the trace's distinct states.
        assertContains(layout.agentStateColors.keys, "Infected")
        assertContains(layout.agentStateColors.keys, "Recovered")
    }

    private fun paintedPixels(replay: ReplayModel): Int {
        val canvas = SimulationCanvas().apply { setSize(600, 400); this.replay = replay; currentTime = 0.0 }
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()
        var painted = 0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (image.getRGB(x, y) and 0xffffff != 0xffffff) painted++
        return painted
    }
}
