package ksl.app.swing.common.notification

import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSpec
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NotificationsTest {

    private fun makePane(): JLayeredPane = JLayeredPane().apply {
        preferredSize = Dimension(400, 600)
        size = preferredSize
    }

    @Test
    fun `show adds a card to the layered pane`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            notifications.show(NotificationSpec("Saved", dismissAfter = null))
            assertEquals(1, notifications.visibleCountForTest)
        }
    }

    @Test
    fun `dismissNewest removes the topmost card`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            notifications.show(NotificationSpec("A", dismissAfter = null))
            notifications.show(NotificationSpec("B", dismissAfter = null))
            assertEquals(2, notifications.visibleCountForTest)
            notifications.dismissNewestForTest()
            assertEquals(1, notifications.visibleCountForTest)
        }
    }

    @Test
    fun `dismissAll clears the stack`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            for (i in 1..3) notifications.show(NotificationSpec("n$i", dismissAfter = null))
            notifications.dismissAll()
            assertEquals(0, notifications.visibleCountForTest)
            assertEquals(0, notifications.droppedCountForTest)
        }
    }

    @Test
    fun `overflow drops oldest cards and surfaces the count`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane, maxVisible = 2) }
        onEdt {
            notifications.show(NotificationSpec("a", dismissAfter = null))
            notifications.show(NotificationSpec("b", dismissAfter = null))
            notifications.show(NotificationSpec("c", dismissAfter = null))    // drops "a"
            notifications.show(NotificationSpec("d", dismissAfter = null))    // drops "b"
            assertEquals(2, notifications.visibleCountForTest)
            assertEquals(2, notifications.droppedCountForTest)
            val overflow = notifications.overflowTextForTest
            assertNotNull(overflow)
            assertTrue(overflow!!.contains("2"), "expected '+2 more', got $overflow")
        }
    }

    @Test
    fun `severity defaults yield non-null durations`() {
        assertEquals(5.seconds, NotificationSpec.defaultDismissAfter(NotificationSeverity.INFO))
        assertEquals(5.seconds, NotificationSpec.defaultDismissAfter(NotificationSeverity.WARNING))
        assertEquals(8.seconds, NotificationSpec.defaultDismissAfter(NotificationSeverity.ERROR))

        val s = NotificationSpec("hi")
        assertEquals(5.seconds, s.dismissAfter)
    }

    @Test
    fun `auto-dismiss removes the card after the configured duration`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            notifications.show(NotificationSpec("transient", dismissAfter = 50.milliseconds))
            assertEquals(1, notifications.visibleCountForTest)
        }
        // Wait for the Swing Timer to fire.  The Timer runs on the EDT;
        // invokeAndWait drains pending EDT events including timer callbacks.
        Thread.sleep(150)
        onEdt {
            assertEquals(0, notifications.visibleCountForTest)
        }
    }

    @Test
    fun `manual-dismiss-only when dismissAfter is null leaves the card up`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            notifications.show(NotificationSpec("sticky", dismissAfter = null))
        }
        Thread.sleep(120)
        onEdt {
            assertEquals(1, notifications.visibleCountForTest, "no timer means no auto-dismiss")
        }
    }

    @Test
    fun `convenience show with message and severity sets the severity default duration`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            notifications.show("hi", NotificationSeverity.WARNING)
            assertEquals(1, notifications.visibleCountForTest)
        }
    }

    @Test
    fun `overflow indicator clears when remaining cards drop below maxVisible`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane, maxVisible = 2) }
        onEdt {
            notifications.show(NotificationSpec("a", dismissAfter = null))
            notifications.show(NotificationSpec("b", dismissAfter = null))
            notifications.show(NotificationSpec("c", dismissAfter = null))    // drops "a"
            assertEquals("+1 more", notifications.overflowTextForTest)
            notifications.dismissOldestForTest()    // remove "b"
            assertNull(notifications.overflowTextForTest, "overflow chip should clear when count drops below max")
        }
    }

    @Test
    fun `MIN_DISMISS_MS coerces a too-short duration without throwing`() {
        val pane = onEdt { makePane() }
        val notifications = onEdt { Notifications(pane) }
        onEdt {
            // Duration of 1 ns rounds to 0 ms; the implementation coerces up to MIN_DISMISS_MS.
            notifications.show(NotificationSpec("near-zero", dismissAfter = Duration.ZERO))
            assertEquals(1, notifications.visibleCountForTest)
        }
        Thread.sleep(50)
        onEdt {
            assertEquals(0, notifications.visibleCountForTest)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
