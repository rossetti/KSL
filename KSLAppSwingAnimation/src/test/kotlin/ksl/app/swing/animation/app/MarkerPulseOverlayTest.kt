package ksl.app.swing.animation.app

import ksl.animation.OverlaySpec
import ksl.app.swing.animation.examples.AnimationDemo
import ksl.examples.general.animationbundle.Example15DroneDelivery
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * G-animated: transient marker-pulse overlay. The drone-delivery model reports a pulse at each drop-off point
 * when a delivery completes (`reportMarkerPulse`); those are captured only when the overlay is enabled (off by
 * default = zero cost), and replay exposes the pulses "live" at a time with a 0→1 fade progress.
 */
class MarkerPulseOverlayTest {

    private fun droneReplay(baseName: String, overlays: OverlaySpec): ReplayModel {
        val m = Example15DroneDelivery.buildModel().apply { lengthOfReplication = 150.0 }
        val files = AnimationDemo.generate(m, Example15DroneDelivery.buildLayout(m), baseName = baseName, overlays = overlays)
        return ReplayModel.build(AnimationSource.load(files.layoutFile, files.traceFile))
    }

    @Test
    fun `marker pulses are captured only when enabled`() {
        val off = droneReplay("DronePulsesOff", OverlaySpec.OFF)
        assertTrue(!off.hasMarkerPulses, "no pulses captured by default — zero cost")

        val on = droneReplay("DronePulsesOn", OverlaySpec(markerPulses = true))
        assertTrue(on.hasMarkerPulses, "pulses captured when enabled")
    }

    @Test
    fun `active pulses report a 0 to 1 progress inside their window`() {
        val on = droneReplay("DronePulsesWindow", OverlaySpec(markerPulses = true))
        // Sample the run densely; at some instant at least one pulse must be live, and every reported pulse's
        // progress must lie in [0, 1] (the renderer relies on this to expand/fade the ring).
        val span = on.timeRange.endInclusive - on.timeRange.start
        var sawLive = false
        var step = on.timeRange.start
        while (step <= on.timeRange.endInclusive) {
            val active = on.markerPulsesActiveAt(step)
            if (active.isNotEmpty()) sawLive = true
            assertTrue(active.all { it.progress in 0.0..1.0 }, "pulse progress must be a 0..1 fraction")
            step += span / 200
        }
        assertTrue(sawLive, "expected at least one live marker pulse during the run")
    }
}
