package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.ElementKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure [AnimationLayout] editing transforms (9F.2). */
class LayoutEditingTest {

    @Test
    fun `add places an element and is idempotent`() {
        var layout = AnimationLayout()
        assertFalse(layout.isPlaced(ElementKind.RESOURCE, "Server"))
        layout = layout.withElementAdded(ElementKind.RESOURCE, "Server", 10.0, 20.0)
        assertTrue(layout.isPlaced(ElementKind.RESOURCE, "Server"))
        assertEquals(10.0, layout.positionOf(ElementKind.RESOURCE, "Server")?.x)
        // Re-adding the same element is a no-op (no duplicate).
        val again = layout.withElementAdded(ElementKind.RESOURCE, "Server", 99.0, 99.0)
        assertEquals(1, again.resources.size)
        assertEquals(10.0, again.positionOf(ElementKind.RESOURCE, "Server")?.x, "position unchanged by re-add")
    }

    @Test
    fun `move repositions a placed element and leaves others alone`() {
        val layout = AnimationLayout()
            .withElementAdded(ElementKind.QUEUE, "Q1", 0.0, 0.0)
            .withElementAdded(ElementKind.QUEUE, "Q2", 5.0, 5.0)
            .withElementMoved(ElementKind.QUEUE, "Q1", 100.0, 200.0)
        assertEquals(100.0, layout.positionOf(ElementKind.QUEUE, "Q1")?.x)
        assertEquals(200.0, layout.positionOf(ElementKind.QUEUE, "Q1")?.y)
        assertEquals(5.0, layout.positionOf(ElementKind.QUEUE, "Q2")?.x, "Q2 untouched")
    }

    @Test
    fun `remove deletes only the named element`() {
        val layout = AnimationLayout()
            .withElementAdded(ElementKind.STATION, "S1", 0.0, 0.0)
            .withElementAdded(ElementKind.STATION, "S2", 0.0, 0.0)
            .withElementRemoved(ElementKind.STATION, "S1")
        assertFalse(layout.isPlaced(ElementKind.STATION, "S1"))
        assertTrue(layout.isPlaced(ElementKind.STATION, "S2"))
    }

    @Test
    fun `responses and counters both map to value displays`() {
        val layout = AnimationLayout()
            .withElementAdded(ElementKind.RESPONSE, "NumInSystem", 0.0, 0.0)
            .withElementAdded(ElementKind.COUNTER, "NumServed", 0.0, 0.0)
        assertEquals(2, layout.values.size)
        assertTrue(layout.isPlaced(ElementKind.RESPONSE, "NumInSystem"))
        assertTrue(layout.isPlaced(ElementKind.COUNTER, "NumServed"))
    }

    @Test
    fun `canvas resize clamps to a minimum`() {
        val layout = AnimationLayout().withCanvasSize(1200.0, 5.0)
        assertEquals(1200.0, layout.width)
        assertEquals(100.0, layout.height, "height clamped to the minimum")
    }

    @Test
    fun `pickElement returns the nearest placed element within the radius`() {
        val layout = AnimationLayout()
            .withElementAdded(ElementKind.RESOURCE, "A", 100.0, 100.0)
            .withElementAdded(ElementKind.QUEUE, "B", 200.0, 100.0)
        // Near A.
        assertEquals(ElementKind.RESOURCE to "A", layout.pickElement(104.0, 98.0, radius = 15.0))
        // Between the two but closer to B.
        assertEquals(ElementKind.QUEUE to "B", layout.pickElement(190.0, 100.0, radius = 15.0))
        // Too far from anything.
        assertNull(layout.pickElement(160.0, 100.0, radius = 15.0))
    }

    @Test
    fun `movable resources are placed with an editable home-rest position (P6b)`() {
        var layout = AnimationLayout()
        layout = layout.withElementAdded(ElementKind.MOVABLE_RESOURCE, "AGV1", 12.0, 34.0)
        assertTrue(layout.isPlaced(ElementKind.MOVABLE_RESOURCE, "AGV1"))
        assertTrue(layout.movableResources.any { it.name == "AGV1" })
        // The mover now carries a parked/home position (drawn at rest), and it is editable via move.
        assertEquals(12.0, layout.positionOf(ElementKind.MOVABLE_RESOURCE, "AGV1")?.x)
        layout = layout.withElementMoved(ElementKind.MOVABLE_RESOURCE, "AGV1", 99.0, 88.0)
        assertEquals(99.0, layout.positionOf(ElementKind.MOVABLE_RESOURCE, "AGV1")?.x)
        assertEquals(88.0, layout.positionOf(ElementKind.MOVABLE_RESOURCE, "AGV1")?.y)
        layout = layout.withElementRemoved(ElementKind.MOVABLE_RESOURCE, "AGV1")
        assertFalse(layout.isPlaced(ElementKind.MOVABLE_RESOURCE, "AGV1"))
    }

    @Test
    fun `object-class styles add, replace and remove by type name`() {
        var layout = AnimationLayout()
            .withObjectClass("Customer", ksl.animation.LayoutShape.CIRCLE, "#ff0000", 12.0)
        assertEquals(1, layout.objectClasses.size)
        assertEquals(ksl.animation.LayoutShape.CIRCLE, layout.objectClasses.first().shape)
        // Same type name replaces (no duplicate).
        layout = layout.withObjectClass("Customer", ksl.animation.LayoutShape.SQUARE, "#00ff00", 20.0)
        assertEquals(1, layout.objectClasses.size)
        assertEquals(ksl.animation.LayoutShape.SQUARE, layout.objectClasses.first().shape)
        layout = layout.withObjectClassRemoved("Customer")
        assertTrue(layout.objectClasses.isEmpty())
    }

    @Test
    fun `unsupported kinds are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AnimationLayout().withElementAdded(ElementKind.AGENT, "a", 0.0, 0.0)
        }
        assertNull(runCatching { AnimationLayout().positionOf(ElementKind.RESOURCE, "absent") }.getOrThrow())
    }

    @Test
    fun `process colors add, replace and remove`() {
        val l = AnimationLayout()
            .withProcessColor("Triage", "#ff7f0e")
            .withProcessColor("Exam", "#d62728")
        assertEquals(mapOf("Triage" to "#ff7f0e", "Exam" to "#d62728"), l.processColors)
        // Re-adding the same process replaces its color.
        val replaced = l.withProcessColor("Triage", "#00ff00")
        assertEquals("#00ff00", replaced.processColors["Triage"])
        assertEquals(2, replaced.processColors.size)
        // Removal drops only the named process.
        val removed = replaced.withProcessColorRemoved("Exam")
        assertEquals(setOf("Triage"), removed.processColors.keys)
    }
}
