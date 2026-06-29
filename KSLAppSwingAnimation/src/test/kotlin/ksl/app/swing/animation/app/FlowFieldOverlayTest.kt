package ksl.app.swing.animation.app

import ksl.animation.OverlaySpec
import ksl.app.swing.animation.examples.AnimationDemo
import ksl.examples.general.animationbundle.Example05PedestrianCrowd
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * G11: the flow-field gradient overlay is captured only when explicitly enabled (off by default = zero cost),
 * and when present it carries a usable gradient (cells + a max distance for the color ramp).
 */
class FlowFieldOverlayTest {

    private fun crowdReplay(baseName: String, overlays: OverlaySpec): ReplayModel {
        val m = Example05PedestrianCrowd.buildModel().apply { lengthOfReplication = 5.0 } // short run; field is built at init
        val files = AnimationDemo.generate(m, Example05PedestrianCrowd.buildLayout(m), baseName = baseName, overlays = overlays)
        return ReplayModel.build(AnimationSource.load(files.layoutFile, files.traceFile))
    }

    @Test
    fun `flow field is captured only when enabled, and carries a usable gradient (G11)`() {
        val off = crowdReplay("CrowdFFoff", OverlaySpec.OFF)
        assertTrue(off.flowFieldOverlays.isEmpty(), "no overlay captured by default — zero cost")

        val on = crowdReplay("CrowdFFon", OverlaySpec(flowField = true))
        assertTrue(on.flowFieldOverlays.isNotEmpty(), "flow field captured when enabled")
        val ff = on.flowFieldOverlays.first()
        assertTrue(ff.cells.isNotEmpty(), "the gradient has cells")
        assertTrue(ff.maxDistance > 0.0, "a max distance anchors the color ramp")
        assertTrue(ff.cellSize > 0.0, "cells have a world size to place them")
        // Renders without error and the frame is populated (heatmap + agents).
        val files = AnimationDemo.generate(
            Example05PedestrianCrowd.buildModel().apply { lengthOfReplication = 5.0 },
            Example05PedestrianCrowd.buildLayout(Example05PedestrianCrowd.buildModel()),
            baseName = "CrowdFFrender", overlays = OverlaySpec(flowField = true)
        )
        val image = AnimationDemo.renderFrame(files, fraction = 0.2)
        var nonWhite = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) and 0xffffff != 0xffffff) nonWhite++
        assertTrue(nonWhite > 500, "expected a populated frame with the heatmap drawn")
    }
}
