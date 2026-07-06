package ksl.animation.replay

import ksl.animation.AnimationLayout
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.animation.SpatialSpaceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovery C1: auto-layout seeds an editable, space-scaled object-class per discovered type, instead of
 * leaving the renderer's invisible default size (the agent "blob").
 */
class ObjectClassSeedingTest {

    @Test
    fun `glyph size is about 0_7 of a grid cell`() {
        assertEquals(0.7, objectGlyphSize(listOf(SpatialSpaceDescriptor.Grid("g", 20, 20, 1.0)))!!, 1e-9)
        assertEquals(16.8, objectGlyphSize(listOf(SpatialSpaceDescriptor.Grid("g", 10, 10, 24.0)))!!, 1e-9)
    }

    @Test
    fun `glyph size is a fraction of a continuous space's shorter span`() {
        // shorter span = 50 → 0.03 * 50 = 1.5
        val size = objectGlyphSize(listOf(SpatialSpaceDescriptor.Continuous("c", 0.0, 100.0, 0.0, 50.0)))
        assertEquals(1.5, size!!, 1e-9)
    }

    @Test
    fun `glyph size is a fraction of a network's node span`() {
        val net = SpatialSpaceDescriptor.Network(
            "n",
            nodes = listOf(
                ksl.animation.NetworkNode("a", ksl.animation.LayoutPoint(0.0, 0.0)),
                ksl.animation.NetworkNode("b", ksl.animation.LayoutPoint(100.0, 40.0))
            )
        )
        assertEquals(1.2, objectGlyphSize(listOf(net))!!, 1e-9) // shorter span = 40 → 0.03 * 40
    }

    @Test
    fun `glyph size is null when there is no planar space`() {
        assertNull(objectGlyphSize(emptyList()))
        assertNull(objectGlyphSize(listOf(SpatialSpaceDescriptor.Network("n"))))
    }

    @Test
    fun `seeding adds a sized, colored circle per type`() {
        val seeded = AnimationLayout(title = "t").withSeededObjectClasses(listOf("Person", "Drone"), 0.7)
        assertEquals(2, seeded.objectClasses.size)
        seeded.objectClasses.forEach {
            assertEquals(LayoutShape.CIRCLE, it.shape)
            assertEquals(0.7, it.size, 1e-9)
            assertTrue(it.color.startsWith("#"), "seeded a palette color, got ${it.color}")
        }
        // Distinct palette colors for distinct types.
        assertEquals(2, seeded.objectClasses.map { it.color }.distinct().size)
    }

    @Test
    fun `null size falls back to the model default`() {
        val seeded = AnimationLayout(title = "t").withSeededObjectClasses(listOf("Customer"), null)
        assertEquals(ObjectClassDefinition(typeName = "Customer").size, seeded.objectClasses.single().size, 1e-9)
    }

    @Test
    fun `seeding preserves an existing object-class and is idempotent`() {
        val authored = AnimationLayout(title = "t").copy(
            objectClasses = listOf(ObjectClassDefinition("Person", LayoutShape.SQUARE, "#ff0000", 5.0))
        )
        val once = authored.withSeededObjectClasses(listOf("Person", "Drone"), 0.7)
        // Person is untouched (authored square/red/5), Drone is added.
        val person = once.objectClasses.single { it.typeName == "Person" }
        assertEquals(LayoutShape.SQUARE, person.shape)
        assertEquals("#ff0000", person.color)
        assertEquals(5.0, person.size, 1e-9)
        assertEquals(2, once.objectClasses.size)
        // Applying again changes nothing.
        assertEquals(once, once.withSeededObjectClasses(listOf("Person", "Drone"), 0.7))
    }

    @Test
    fun `blank type names are skipped`() {
        val seeded = AnimationLayout(title = "t").withSeededObjectClasses(listOf("", "  ", "A"), 1.0)
        assertEquals(listOf("A"), seeded.objectClasses.map { it.typeName })
    }
}
