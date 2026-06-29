package ksl.app.swing.animation.playback

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [PlaybackPanel.applyAutoSpeed]: a long run gets a speed that plays it in roughly the target
 * wall-clock window, instead of the glacial 1x default that made playback look frozen.
 */
class PlaybackAutoSpeedTest {

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `auto speed scales a long run toward the target window`() {
        val controller = PlaybackController(0.0..480.0)
        onEdt {
            val panel = PlaybackPanel(controller)
            panel.applyAutoSpeed(480.0, targetSeconds = 25.0)
        }
        // 480/25 = 19.2, rounded to a tidy 1/2/5x10^n -> 20x; a 480-unit run then plays in ~24s.
        assertEquals(20.0, controller.speed, "expected a tidy auto speed near 480/25")
        assertTrue(480.0 / controller.speed <= 30.0, "run should play within ~30s of wall-clock")
    }

    @Test
    fun `auto speed is a no-op for an empty range`() {
        val controller = PlaybackController(0.0..0.0)
        controller.speed = 1.0
        onEdt { PlaybackPanel(controller).applyAutoSpeed(0.0) }
        assertEquals(1.0, controller.speed)
    }

    @Test
    fun `short runs are not slowed below real time`() {
        val controller = PlaybackController(0.0..10.0)
        onEdt { PlaybackPanel(controller).applyAutoSpeed(10.0, targetSeconds = 25.0) }
        // 10/25 = 0.4 -> tidy 0.5, floored at 0.25; never below 0.25.
        assertTrue(controller.speed >= 0.25, "speed floored at 0.25x, was ${controller.speed}")
    }
}
