package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.ElementKind
import ksl.app.swing.animation.app.withElementAdded
import ksl.app.swing.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the Replay trace × layout compatibility check (9F.4). */
class ReplayCompatibilityTest {

    private val events = listOf(
        AnimationEvent.QueueLengthChanged(0.0, "WaitQ", 1),
        AnimationEvent.ResourceStateChanged(0.0, "Server", "Server_Busy", busyUnits = 1, capacity = 1),
        AnimationEvent.ResponseObserved(0.0, "NumInSystem", 1.0)
    )

    private fun model() = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

    @Test
    fun `a layout matching the trace is fully covered`() {
        val layout = AnimationLayout()
            .withElementAdded(ElementKind.RESOURCE, "Server", 0.0, 0.0)
            .withElementAdded(ElementKind.QUEUE, "WaitQ", 0.0, 0.0)
        val report = layoutTraceCompatibility(layout, model())
        assertTrue(report.isFullyCovered, report.summary())
        assertContains(report.summary(), "matches")
    }

    @Test
    fun `bindings without data and unlaid animated elements are both reported`() {
        // Places Server (present) + Ghost (absent); leaves WaitQ unplaced.
        val layout = AnimationLayout()
            .withElementAdded(ElementKind.RESOURCE, "Server", 0.0, 0.0)
            .withElementAdded(ElementKind.RESOURCE, "Ghost", 0.0, 0.0)
        val report = layoutTraceCompatibility(layout, model())
        assertFalse(report.isFullyCovered)
        assertTrue(report.bindingsWithoutData.any { it.contains("Ghost") }, "Ghost has no data in the trace")
        assertTrue(report.animatedButUnlaid.any { it.contains("WaitQ") }, "WaitQ is present but unplaced")
        assertContains(report.summary(), "no data")
    }
}
