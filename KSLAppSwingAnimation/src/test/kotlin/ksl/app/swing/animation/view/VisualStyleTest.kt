package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.animation.ResourceLayoutElement
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Headless unit tests for [VisualStyle]: hex parsing, layout-driven vs. auto-assigned object colors,
 * and resource state coloring by substring (handles resource-qualified states like "Worker_Busy").
 */
class VisualStyleTest {

    @Test
    fun `parseColor handles rrggbb, aarrggbb, and malformed input`() {
        assertEquals(Color(0x12, 0x34, 0x56), VisualStyle.parseColor("#123456"))
        assertEquals(Color(0x12, 0x34, 0x56), VisualStyle.parseColor("123456"), "leading # optional")
        val withAlpha = VisualStyle.parseColor("#80ff0000")
        assertEquals(0xff, withAlpha.red); assertEquals(0x80, withAlpha.alpha)
        assertEquals(Color.GRAY, VisualStyle.parseColor("nonsense"), "malformed -> gray")
    }

    @Test
    fun `object color uses layout when present and a stable palette otherwise`() {
        val layout = AnimationLayout(
            objectClasses = listOf(ObjectClassDefinition(typeName = "Part", color = "#abcdef", shape = LayoutShape.SQUARE, size = 14.0))
        )
        val style = VisualStyle(layout)
        assertEquals(Color(0xab, 0xcd, 0xef), style.objectColor("Part"))
        assertEquals(LayoutShape.SQUARE, style.objectShape("Part"))
        assertEquals(14.0, style.objectSize("Part"))

        // Unknown types get a palette color that is stable across calls and differs between types.
        val c1 = style.objectColor("Other")
        assertEquals(c1, style.objectColor("Other"), "same type -> same color")
        assertNotEquals(c1, style.objectColor("Another"), "distinct types -> distinct palette slots")
        assertEquals(LayoutShape.CIRCLE, style.objectShape("Other"), "default shape")
    }

    @Test
    fun `resource color matches state by substring`() {
        val res = ResourceLayoutElement(
            resourceName = "Worker", position = LayoutPoint(0.0, 0.0),
            idleColor = "#00ff00", busyColor = "#ff0000", failedColor = "#0000ff", inactiveColor = "#cccccc"
        )
        val style = VisualStyle(null)
        assertEquals(Color(0, 0xff, 0), style.resourceColor(res, null), "null state -> idle")
        assertEquals(Color(0xff, 0, 0), style.resourceColor(res, "Worker_Busy"), "qualified busy")
        assertEquals(Color(0, 0, 0xff), style.resourceColor(res, "Worker_Failed"), "qualified failed")
        assertEquals(Color(0xcc, 0xcc, 0xcc), style.resourceColor(res, "Inactive"))
        assertEquals(Color(0, 0xff, 0), style.resourceColor(res, "Idle"), "idle")
    }

    @Test
    fun `resource image ref matches state by substring and is null when unset`() {
        val res = ResourceLayoutElement(
            resourceName = "Worker", position = LayoutPoint(0.0, 0.0),
            idleImage = "idle.png", busyImage = "busy.png", failedImage = "failed.png" // inactiveImage left null
        )
        val style = VisualStyle(null)
        assertEquals("idle.png", style.resourceImageRef(res, null), "null state -> idle image")
        assertEquals("busy.png", style.resourceImageRef(res, "Worker_Busy"), "qualified busy")
        assertEquals("failed.png", style.resourceImageRef(res, "Worker_Failed"), "qualified failed")
        assertEquals(null, style.resourceImageRef(res, "Inactive"), "no inactive image -> null (color fallback)")
        assertEquals("idle.png", style.resourceImageRef(res, "Idle"))
    }

    @Test
    fun `agent state color prefers exact then longest key (no Uninformed-Informed collision)`() {
        // "Uninformed" contains "Informed"; if a shorter substring key captured the more specific state, the
        // rumor model (Ex16) would render every node one color and the spread would be invisible.
        val layout = AnimationLayout(agentStateColors = mapOf("Informed" to "#1f77b4", "Uninformed" to "#d62728"))
        val style = VisualStyle(layout)
        assertEquals(Color(0x1f, 0x77, 0xb4), style.agentStateColor("Informed"), "exact match")
        assertEquals(Color(0xd6, 0x27, 0x28), style.agentStateColor("Uninformed"), "specific state, not the 'Informed' substring")
        assertEquals(Color(0x1f, 0x77, 0xb4), style.agentStateColor("Person_Informed"), "qualified state matches by (longest) substring")
        assertEquals(null, style.agentStateColor("Curious"), "no match -> null (type-color fallback)")
    }

    @Test
    fun `process color prefers exact then longest key`() {
        val layout = AnimationLayout(processColors = mapOf("Serve" to "#1f77b4", "ServeVIP" to "#d62728"))
        val style = VisualStyle(layout)
        assertEquals(Color(0xd6, 0x27, 0x28), style.processColor("ServeVIP"), "exact match beats shorter 'Serve' substring")
        assertEquals(Color(0xd6, 0x27, 0x28), style.processColor("ServeVIP-2"), "longest containing key when no exact match")
        assertEquals(Color(0x1f, 0x77, 0xb4), style.processColor("Serve"), "exact match")
        assertEquals(null, style.processColor("Idle"), "no match -> null")
    }
}
