package ksl.app.swing.animation.app

import ksl.animation.OverlaySpec
import ksl.app.swing.animation.examples.AnimationDemo
import ksl.examples.general.animationbundle.Example06WarehouseAGV
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import kotlin.test.Test
import kotlin.test.assertTrue
import ksl.app.animation.io.load

/**
 * G12: planned-route overlay. The warehouse AGVs report the routes they plan (A* on the grid); those are
 * captured only when the overlay is enabled (off by default = zero cost), and replay can draw them as polylines.
 */
class PlannedPathOverlayTest {

    private fun agvReplay(baseName: String, overlays: OverlaySpec): ReplayModel {
        val m = Example06WarehouseAGV.buildModel().apply { lengthOfReplication = 30.0 }
        val files = AnimationDemo.generate(m, Example06WarehouseAGV.buildLayout(m), baseName = baseName, overlays = overlays)
        return ReplayModel.build(AnimationSource.load(files.layoutFile, files.traceFile))
    }

    @Test
    fun `planned paths are captured only when enabled and carry usable routes (G12)`() {
        val off = agvReplay("AgvPathsOff", OverlaySpec.OFF)
        assertTrue(off.agentsWithPaths.isEmpty(), "no routes captured by default — zero cost")

        val on = agvReplay("AgvPathsOn", OverlaySpec(plannedPaths = true))
        assertTrue(on.agentsWithPaths.isNotEmpty(), "routes captured when enabled: ${on.agentsWithPaths}")
        // At least one agent has a route with >= 2 points at some time across the run.
        val hasRealRoute = on.agentsWithPaths.any { name ->
            generateSequence(on.timeRange.start) { it + (on.timeRange.endInclusive - on.timeRange.start) / 20 }
                .takeWhile { it <= on.timeRange.endInclusive }
                .any { t -> (on.plannedPathAt(name, t)?.size ?: 0) >= 2 }
        }
        assertTrue(hasRealRoute, "at least one AGV has a drawable (>=2 point) planned route")

        val image = AnimationDemo.renderFrame(agvFiles("AgvPathsRender", OverlaySpec(plannedPaths = true)), fraction = 0.5)
        var nonWhite = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) and 0xffffff != 0xffffff) nonWhite++
        assertTrue(nonWhite > 500, "expected a populated frame with routes drawn")
    }

    private fun agvFiles(baseName: String, overlays: OverlaySpec): AnimationDemo.TraceFiles {
        val m = Example06WarehouseAGV.buildModel().apply { lengthOfReplication = 30.0 }
        return AnimationDemo.generate(m, Example06WarehouseAGV.buildLayout(m), baseName = baseName, overlays = overlays)
    }
}
