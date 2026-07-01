package ksl.animation

import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies an [AnimationLayout] round-trips through the TOML codec (8E.2), including the sealed
 *  spatial-space hierarchy and the agent-state-color map. */
class AnimationLayoutTomlTest {

    @Test
    fun `layout round-trips through TOML`() {
        val layout = AnimationLayout(
            title = "Demo", width = 800.0, height = 600.0,
            objectClasses = listOf(ObjectClassDefinition("Part", color = "#123456", size = 14.0)),
            queues = listOf(QueueLayoutElement("Q", LayoutPoint(1.0, 2.0))),
            resources = listOf(ResourceLayoutElement("R", LayoutPoint(3.0, 4.0), size = 20.0)),
            spaces = listOf(
                SpatialSpaceDescriptor.Grid("grid", cols = 10, rows = 8, cellSize = 1.0),
                SpatialSpaceDescriptor.Continuous("plane", 0.0, 100.0, 0.0, 80.0)
            ),
            agentStateColors = mapOf("Working" to "#2ca02c", "Idle" to "#999999"),
            summaries = listOf(SummaryDisplayElement("TimeInSystem", LayoutPoint(5.0, 6.0), label = "TiS"))
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
    }

    @Test
    fun `locations round-trip through TOML (placed and unplaced)`() {
        val layout = AnimationLayout(
            stations = listOf(StationLayoutElement("S1", LayoutPoint(1.0, 2.0))),
            locations = listOf(
                LocationLayoutElement("Depot", LayoutPoint(10.0, 20.0), label = "Depot (pickup)"),
                LocationLayoutElement("drop-0") // unplaced: null position
            )
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
        assertEquals(2, back.locations.size)
        assertEquals(null, back.locations.first { it.locationName == "drop-0" }.position, "unplaced location keeps a null position")
    }

    @Test
    fun `a layout without a locations block still loads (wire-safe default)`() {
        val back = AnimationLayout.fromToml(AnimationLayout(stations = listOf(StationLayoutElement("A", LayoutPoint(0.0, 0.0)))).toToml())
        assertEquals(emptyList(), back.locations)
    }

    @Test
    fun `conveyor routes round-trip through TOML`() {
        val layout = AnimationLayout(
            conveyors = listOf(ksl.animation.ConveyorLayoutElement(
                conveyorName = "mainLine",
                segments = listOf(
                    ksl.animation.SegmentRoute("Enter", "Inspect"),                                  // straight
                    ksl.animation.SegmentRoute("Inspect", "Pack", waypoints = listOf(LayoutPoint(40.0, 50.0), LayoutPoint(60.0, 50.0)))
                ),
                width = 10.0, color = "#445566", showDirection = false, label = "belt"
            ))
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
        assertEquals(2, back.conveyors.first().segments.size)
        assertEquals(listOf(LayoutPoint(40.0, 50.0), LayoutPoint(60.0, 50.0)), back.conveyors.first().segments[1].waypoints)
    }

    @Test
    fun `element label overrides round-trip through TOML`() {
        val layout = AnimationLayout(
            queues = listOf(QueueLayoutElement("Q", LayoutPoint(1.0, 2.0))),
            labels = listOf(ksl.animation.ElementLabel(ksl.animation.ElementKind.QUEUE, "Q", text = "Intake", dx = 15.0, dy = -20.0, visible = false, valueDx = 8.0, valueDy = 24.0, valueVisible = false))
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
        assertEquals("Intake", back.labels.first().text)
        assertEquals(false, back.labels.first().visible)
    }

    @Test
    fun `movable resource saved position round-trips through TOML`() {
        val layout = AnimationLayout(
            movableResources = listOf(ksl.animation.MovableResourceLayoutElement(
                name = "AGV1", position = LayoutPoint(42.0, 99.0), busyColor = "#ff0000", homeBase = "Depot"
            ))
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
        assertEquals(LayoutPoint(42.0, 99.0), back.movableResources.first().position)
        assertEquals("Depot", back.movableResources.first().homeBase)
    }

    @Test
    fun `resource per-state images round-trip through TOML`() {
        val layout = AnimationLayout(
            resources = listOf(ResourceLayoutElement(
                "R", LayoutPoint(3.0, 4.0), size = 20.0,
                idleImage = "idle.png", busyImage = "busy.png", failedImage = "failed.png" // inactiveImage stays null
            ))
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
        assertEquals("busy.png", back.resources.first().busyImage)
        assertEquals(null, back.resources.first().inactiveImage)
    }

    @Test
    fun `resource busy-over-capacity readout toggle round-trips through TOML`() {
        val layout = AnimationLayout(
            resources = listOf(ResourceLayoutElement("Pharmacists", LayoutPoint(3.0, 4.0), showValue = true))
        )
        val back = AnimationLayout.fromToml(layout.toToml())
        assertEquals(layout, back)
        assertEquals(true, back.resources.first().showValue)
    }
}
