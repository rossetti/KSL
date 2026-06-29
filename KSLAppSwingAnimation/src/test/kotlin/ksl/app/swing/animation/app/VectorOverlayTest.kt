package ksl.app.swing.animation.app

import ksl.animation.OverlaySpec
import ksl.app.swing.animation.examples.AnimationDemo
import ksl.examples.general.animationbundle.Example11Flocking
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * G10: velocity/force vector overlay — the volume-sensitive one. Captured only when enabled (off by default
 * = zero cost), rate-limited by the sampler (not every integration step), and drawable as arrows.
 */
class VectorOverlayTest {

    private fun flockReplay(baseName: String, overlays: OverlaySpec): ReplayModel {
        val m = Example11Flocking.buildModel().apply { lengthOfReplication = 4.0 } // short; sampler is rate-limited
        val files = AnimationDemo.generate(m, Example11Flocking.buildLayout(m), baseName = baseName, overlays = overlays)
        return ReplayModel.build(AnimationSource.load(files.layoutFile, files.traceFile))
    }

    @Test
    fun `vectors are captured only when enabled, sampled, and drawable (G10)`() {
        val off = flockReplay("FlockVecOff", OverlaySpec.OFF)
        assertTrue(off.agentsWithVectors.isEmpty(), "no vectors captured by default — zero cost")

        // Velocity + force, sampled at 2/sec (interval 0.5) over a 4s run.
        val on = flockReplay("FlockVecOn", OverlaySpec(velocities = true, forces = true, vectorSampleInterval = 0.5))
        assertTrue(on.agentsWithVectors.isNotEmpty(), "vectors captured when enabled")
        val name = on.agentsWithVectors.first()
        val v = on.agentVectorAt(name, on.timeRange.endInclusive * 0.5)
        assertTrue(v != null, "a sampled vector is available mid-run")
        assertTrue(v.vx.isFinite() && v.vy.isFinite(), "velocity captured")
        assertTrue(v.fx.isFinite() && v.fy.isFinite(), "net force captured")

        val image = AnimationDemo.renderFrame(
            flockFiles("FlockVecRender", OverlaySpec(velocities = true, forces = true, vectorSampleInterval = 0.5)),
            fraction = 0.5
        )
        var nonWhite = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) and 0xffffff != 0xffffff) nonWhite++
        assertTrue(nonWhite > 500, "expected a populated frame with vector arrows")
    }

    private fun flockFiles(baseName: String, overlays: OverlaySpec): AnimationDemo.TraceFiles {
        val m = Example11Flocking.buildModel().apply { lengthOfReplication = 4.0 }
        return AnimationDemo.generate(m, Example11Flocking.buildLayout(m), baseName = baseName, overlays = overlays)
    }
}
