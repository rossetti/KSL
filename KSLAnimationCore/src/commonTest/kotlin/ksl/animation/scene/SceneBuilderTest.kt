/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.animation.scene

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SpatialSpaceDescriptor
import ksl.app.animation.geom.BoundingBox
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.scene.SceneBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the drawing decisions a scene encodes.
 *
 * These are golden tests over the emitted [DrawCmd] structures, not over rendered pixels or serialized
 * text. Structures are the right thing to compare for two reasons: an image comparison needs a display
 * and reports "something moved" rather than what changed, and a text comparison is not portable — the
 * platforms format an integral `Double` differently, which is why the trace-format tests are split by
 * platform. Asserting on the commands says exactly which decision changed.
 *
 * The scene is what every surface consumes, so a decision pinned here is pinned for the desktop canvas,
 * a browser canvas, and any offscreen renderer at once.
 */
class SceneBuilderTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private fun events(vararg lines: String): List<AnimationEvent> =
        lines.map { AnimationEvent.decodeFromLine(it) }

    private fun sceneOf(
        layout: AnimationLayout?,
        events: List<AnimationEvent>,
        t: Double,
        options: SceneOptions = SceneOptions()
    ): Scene {
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        return SceneBuilder(model, options).build(t)
    }

    /**
     * A one-nurse clinic: a queue growing downward, a single-capacity resource beside it, and — as every
     * real authored layout has — a background spanning the canvas and a clock. The background matters to
     * more than realism: it is part of what makes the layout's content fill its declared canvas, which is
     * what tells the framing rule to honor the author's whitespace instead of zooming to the elements.
     */
    private val clinicLayout = AnimationLayout(
        width = 640.0,
        height = 380.0,
        objectClasses = listOf(
            ObjectClassDefinition("Patient", LayoutShape.CIRCLE, "#1f77b4", 10.0),
            ObjectClassDefinition("VipPatient", LayoutShape.TRIANGLE, "#d62728", 12.0),
        ),
        background = listOf(
            ksl.animation.BackgroundElement(
                kind = ksl.animation.BackgroundKind.RECT,
                points = listOf(LayoutPoint(10.0, 10.0), LayoutPoint(630.0, 370.0)),
                color = "#cccccc"
            )
        ),
        queues = listOf(QueueLayoutElement("Nurse:Q", LayoutPoint(300.0, 200.0), growthDegrees = 90.0, spacing = 12.0, maxShown = 10)),
        resources = listOf(ResourceLayoutElement("Nurse", LayoutPoint(420.0, 200.0), size = 20.0)),
        clocks = listOf(ksl.animation.ClockDisplayElement(LayoutPoint(20.0, 30.0))),
    )

    /**
     * Entity 1 is in service, 2 and 3 wait in the queue, and 4 walks across the canvas — so every way an
     * entity can be represented is exercised at once, which is what makes the no-double-draw assertion
     * meaningful.
     */
    private val clinicEvents = events(
        """{"event":"EntityCreated","simTime":1.0,"entityId":1,"entityType":"Patient"}""",
        """{"event":"EntityCreated","simTime":1.0,"entityId":2,"entityType":"Patient"}""",
        """{"event":"EntityCreated","simTime":1.0,"entityId":3,"entityType":"VipPatient"}""",
        """{"event":"EntityCreated","simTime":1.0,"entityId":4,"entityType":"Patient"}""",
        """{"event":"SeizeQueued","simTime":1.0,"entityId":1,"resourceName":"Nurse","queueName":"Nurse:Q","amountRequested":1}""",
        """{"event":"SeizeAllocated","simTime":1.0,"entityId":1,"resourceName":"Nurse","amountAllocated":1}""",
        """{"event":"ResourceStateChanged","simTime":1.0,"resourceName":"Nurse","state":"Busy","busyUnits":1,"capacity":1}""",
        """{"event":"SeizeQueued","simTime":2.0,"entityId":2,"resourceName":"Nurse","queueName":"Nurse:Q","amountRequested":1}""",
        """{"event":"SeizeQueued","simTime":3.0,"entityId":3,"resourceName":"Nurse","queueName":"Nurse:Q","amountRequested":1}""",
        """{"event":"MoveStarted","simTime":1.0,"entityId":4,"fromX":100.0,"fromY":100.0,"toX":200.0,"toY":100.0,"velocity":10.0,"duration":10.0,"arrivalTime":11.0,"fromZ":0.0,"toZ":0.0,"fromLocationName":null,"toLocationName":null}""",
    )

    // ── layer structure ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun layersAreOrderedBackToFront() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        val names = scene.layers.map { it.name }
        // Backdrop before content, content before annotation, legend last.
        assertTrue(names.indexOf("queues") < names.indexOf("entities"), "queues must be behind entities")
        assertTrue(names.indexOf("resources") < names.indexOf("labels"), "resources must be behind labels")
        assertTrue(names.last() == "legend", "the legend is drawn last, on top; got $names")
    }

    @Test
    fun legendIsDrawnInScreenSpaceSoZoomDoesNotAffectIt() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        val legend = assertNotNull(scene.layer("legend"))
        assertEquals(DrawSpace.SCREEN, legend.space)
        // One swatch + one text per declared object class.
        assertEquals(2, legend.commands.count { it is DrawCmd.Glyph })
        assertEquals(2, legend.commands.count { it is DrawCmd.Text })
    }

    @Test
    fun contentLayersAreDrawnInWorldSpace() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        for (name in listOf("queues", "resources", "entities")) {
            assertEquals(DrawSpace.WORLD, scene.layer(name)?.space, "$name must be world space")
        }
    }

    // ── queues ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun queueMembersGrowFromTheHeadAlongTheGrowthAngle() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        val glyphs = scene.commandsOf("queues").filterIsInstance<DrawCmd.Glyph>()
        assertEquals(2, glyphs.size, "entities 2 and 3 are waiting")
        // growthDegrees = 90 means straight down: x constant, y stepping by the spacing.
        assertEquals(300.0, glyphs[0].cx, 1e-9)
        assertEquals(200.0, glyphs[0].cy, 1e-9)
        assertEquals(300.0, glyphs[1].cx, 1e-9)
        assertEquals(212.0, glyphs[1].cy, 1e-9)
    }

    @Test
    fun queueMembersAreStyledByTheirOwnEntityType() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        val glyphs = scene.commandsOf("queues").filterIsInstance<DrawCmd.Glyph>()
        // Entity 2 is a Patient (circle), entity 3 a VipPatient (triangle) — arrival order is preserved.
        assertEquals(LayoutShape.CIRCLE, glyphs[0].shape)
        assertEquals(LayoutShape.TRIANGLE, glyphs[1].shape)
        assertEquals("#1f77b4", glyphs[0].fill.toHex())
        assertEquals("#d62728", glyphs[1].fill.toHex())
    }

    @Test
    fun anEmptyQueueStillDrawsItsExtentSoItStaysVisibleWhileAuthoring() {
        val scene = sceneOf(clinicLayout, emptyList(), 0.0)
        val lines = scene.commandsOf("queues").filterIsInstance<DrawCmd.Polyline>()
        assertEquals(2, lines.size, "the extent line and the head bar")
        assertTrue(scene.commandsOf("queues").none { it is DrawCmd.Glyph }, "no members to draw")
    }

    @Test
    fun theQueueHeadBarIsPerpendicularToTheGrowthDirection() {
        val scene = sceneOf(clinicLayout, emptyList(), 0.0)
        val lines = scene.commandsOf("queues").filterIsInstance<DrawCmd.Polyline>()
        val bar = lines[1].points
        // Growth is downward, so the bar runs horizontally through the head.
        assertEquals(bar[0].second, bar[1].second, 1e-9, "the bar is horizontal for a downward queue")
        assertTrue(abs(bar[1].first - bar[0].first) > 1.0, "and it has width")
    }

    // ── resources, and no double-drawing ────────────────────────────────────────────────────────────

    @Test
    fun aBusyResourceUsesItsBusyColorAndShowsItsOccupant() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        val cmds = scene.commandsOf("resources")
        val cell = assertNotNull(cmds.filterIsInstance<DrawCmd.Rect>().firstOrNull())
        assertEquals("#d62728", assertNotNull(cell.fill).toHex(), "default busy colour")
        // The entity in service is drawn inside the cell.
        val occupant = assertNotNull(cmds.filterIsInstance<DrawCmd.Glyph>().firstOrNull())
        assertEquals(420.0, occupant.cx, 1e-9)
        assertEquals(200.0, occupant.cy, 1e-9)
    }

    @Test
    fun anIdleResourceUsesItsIdleColor() {
        val scene = sceneOf(clinicLayout, emptyList(), 0.0)
        val cell = assertNotNull(scene.commandsOf("resources").filterIsInstance<DrawCmd.Rect>().firstOrNull())
        assertEquals("#2ca02c", assertNotNull(cell.fill).toHex(), "default idle colour")
    }

    @Test
    fun anEntityIsNeverDrawnTwice() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        // Entity 1 is in service (drawn in its resource cell), 2 and 3 are queue members, and only 4 is in
        // free space. Four entities exist, so exactly four glyphs must be emitted across those layers.
        assertEquals(1, scene.commandsOf("entities").count { it is DrawCmd.Glyph }, "only the walker is free")
        val glyphs = scene.commandsOf("entities").count { it is DrawCmd.Glyph } +
            scene.commandsOf("queues").count { it is DrawCmd.Glyph } +
            scene.commandsOf("resources").count { it is DrawCmd.Glyph }
        assertEquals(4, glyphs, "each entity appears exactly once")
    }

    @Test
    fun aMultiCapacityResourceDrawsOneCellPerUnit() {
        val layout = AnimationLayout(
            width = 400.0, height = 300.0,
            resources = listOf(ResourceLayoutElement("Crew", LayoutPoint(200.0, 150.0), size = 10.0))
        )
        val scene = sceneOf(
            layout,
            events("""{"event":"ResourceStateChanged","simTime":1.0,"resourceName":"Crew","state":"Busy","busyUnits":2,"capacity":3}"""),
            2.0
        )
        val cells = scene.commandsOf("resources").filterIsInstance<DrawCmd.Rect>()
        assertEquals(3, cells.size, "one cell per unit of capacity")
        // Two busy units, then one idle.
        assertEquals("#d62728", assertNotNull(cells[0].fill).toHex())
        assertEquals("#d62728", assertNotNull(cells[1].fill).toHex())
        assertEquals("#2ca02c", assertNotNull(cells[2].fill).toHex())
    }

    // ── moving entities ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMovingEntityIsDrawnAtItsInterpolatedPosition() {
        val layout = AnimationLayout(width = 100.0, height = 100.0)
        val scene = sceneOf(
            layout,
            events(
                """{"event":"EntityCreated","simTime":0.0,"entityId":5,"entityType":"Walker"}""",
                """{"event":"MoveStarted","simTime":0.0,"entityId":5,"fromX":0.0,"fromY":0.0,"toX":40.0,"toY":20.0,"velocity":1.0,"duration":4.0,"arrivalTime":4.0,"fromZ":0.0,"toZ":0.0,"fromLocationName":null,"toLocationName":null}""",
            ),
            2.0 // halfway
        )
        val glyph = assertNotNull(scene.commandsOf("entities").filterIsInstance<DrawCmd.Glyph>().firstOrNull())
        assertEquals(20.0, glyph.cx, 1e-9)
        assertEquals(10.0, glyph.cy, 1e-9)
    }

    // ── agents ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun coLocatedAgentsAreFannedApartSoNoneIsHidden() {
        val layout = AnimationLayout(
            width = 100.0, height = 100.0,
            spaces = listOf(SpatialSpaceDescriptor.Continuous("sky", 0.0, 100.0, 0.0, 100.0)),
            objectClasses = listOf(ObjectClassDefinition("Boid", LayoutShape.CIRCLE, "#1f77b4", 2.0)),
        )
        val scene = sceneOf(
            layout,
            events(
                """{"event":"AgentRegistered","simTime":0.0,"agentName":"b1","agentType":"Boid"}""",
                """{"event":"AgentRegistered","simTime":0.0,"agentName":"b2","agentType":"Boid"}""",
                """{"event":"AgentPositionChanged","simTime":0.0,"agentName":"b1","projectionName":"sky","x":50.0,"y":50.0,"z":0.0}""",
                """{"event":"AgentPositionChanged","simTime":0.0,"agentName":"b2","projectionName":"sky","x":50.0,"y":50.0,"z":0.0}""",
            ),
            0.0
        )
        val glyphs = scene.commandsOf("agents").filterIsInstance<DrawCmd.Glyph>()
        assertEquals(2, glyphs.size)
        val separation = abs(glyphs[0].cx - glyphs[1].cx) + abs(glyphs[0].cy - glyphs[1].cy)
        assertTrue(separation > 1e-6, "two agents at the same point must not be drawn on top of each other")
    }

    @Test
    fun anAgentStateColorOverridesItsTypeColor() {
        val layout = AnimationLayout(
            width = 100.0, height = 100.0,
            objectClasses = listOf(ObjectClassDefinition("Person", LayoutShape.CIRCLE, "#1f77b4", 2.0)),
            agentStateColors = mapOf("Infected" to "#d62728"),
        )
        val scene = sceneOf(
            layout,
            events(
                """{"event":"AgentRegistered","simTime":0.0,"agentName":"p1","agentType":"Person"}""",
                """{"event":"AgentPositionChanged","simTime":0.0,"agentName":"p1","projectionName":"town","x":10.0,"y":10.0,"z":0.0}""",
                """{"event":"AgentStateEntered","simTime":1.0,"agentName":"p1","stateName":"Infected"}""",
            ),
            2.0
        )
        val glyph = assertNotNull(scene.commandsOf("agents").filterIsInstance<DrawCmd.Glyph>().firstOrNull())
        assertEquals("#d62728", glyph.fill.toHex(), "the state colour wins while the agent is in that state")
    }

    // ── world framing (the finding from the spike) ───────────────────────────────────────────────────

    @Test
    fun aSmallSpatialSpaceInsideALargeDefaultCanvasIsFramedOnItsContent() {
        // A 100-unit space declared inside the default 1000x700 canvas. Unioning the two would frame the
        // canvas and leave the content in a few percent of the view, which is what the spike hit.
        val layout = AnimationLayout(
            spaces = listOf(SpatialSpaceDescriptor.Continuous("sky", 0.0, 100.0, 0.0, 100.0))
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), emptyList()))
        val bounds = SceneBuilder(model).worldBounds()
        assertTrue(bounds.width < 200.0, "must frame the 100-unit space, not the 1000-unit canvas; got $bounds")
        assertTrue(bounds.height < 200.0, "same for height; got $bounds")
    }

    @Test
    fun anAuthoredLayoutKeepsItsDeclaredCanvasAndItsWhitespace() {
        val model = ReplayModel.build(AnimationSource(clinicLayout, AnimationTraceHeader(), clinicEvents))
        val bounds = SceneBuilder(model).worldBounds()
        assertEquals(0.0, bounds.minX, 1e-9)
        assertEquals(0.0, bounds.minY, 1e-9)
        assertEquals(640.0, bounds.maxX, 1e-9, "the author's canvas width is preserved")
        assertEquals(380.0, bounds.maxY, 1e-9, "the author's canvas height is preserved")
    }

    /**
     * The other side of the framing rule, and the reason it is a rule rather than "always use the canvas".
     * An authored layout whose elements huddle in one small corner of a big canvas is framed on the
     * elements, because a static view has no zoom control to recover a corner-sized drawing.
     */
    @Test
    fun aLayoutWhoseContentBarelyOccupiesItsCanvasIsFramedOnTheContent() {
        val sparse = AnimationLayout(
            width = 2000.0,
            height = 2000.0,
            resources = listOf(ResourceLayoutElement("Lonely", LayoutPoint(50.0, 50.0), size = 10.0)),
        )
        val model = ReplayModel.build(AnimationSource(sparse, AnimationTraceHeader(), emptyList()))
        val bounds = SceneBuilder(model).worldBounds()
        assertTrue(bounds.width < 500.0, "must zoom to the lone element, not the 2000-unit canvas; got $bounds")
    }

    @Test
    fun movementOutsideTheDeclaredCanvasStillGetsFramed() {
        val layout = AnimationLayout(width = 100.0, height = 100.0)
        val model = ReplayModel.build(
            AnimationSource(
                layout, AnimationTraceHeader(),
                events(
                    """{"event":"EntityCreated","simTime":0.0,"entityId":1,"entityType":"Rover"}""",
                    """{"event":"MoveStarted","simTime":0.0,"entityId":1,"fromX":0.0,"fromY":0.0,"toX":180.0,"toY":40.0,"velocity":1.0,"duration":4.0,"arrivalTime":4.0,"fromZ":0.0,"toZ":0.0,"fromLocationName":null,"toLocationName":null}""",
                )
            )
        )
        val bounds = SceneBuilder(model).worldBounds()
        assertTrue(bounds.maxX >= 180.0, "movement beyond the canvas must still be framed; got $bounds")
    }

    @Test
    fun aTraceWithNoLayoutGetsAGlyphSizeScaledToItsWorld() {
        // No layout at all: the declared default glyph of 10 world units would be a tenth of a 100-unit
        // space. The size must instead come from the world extent.
        val model = ReplayModel.build(
            AnimationSource(
                null, AnimationTraceHeader(),
                events(
                    """{"event":"SpaceDefined","simTime":0.0,"name":"sky","kind":"Continuous","cols":0,"rows":0,"cellSize":1.0,"xMin":0.0,"xMax":100.0,"yMin":0.0,"yMax":100.0,"torus":false}""",
                    """{"event":"AgentRegistered","simTime":0.0,"agentName":"b1","agentType":"Boid"}""",
                    """{"event":"AgentPositionChanged","simTime":0.0,"agentName":"b1","projectionName":"sky","x":50.0,"y":50.0,"z":0.0}""",
                )
            )
        )
        val scene = SceneBuilder(model).build(0.0)
        val glyph = assertNotNull(scene.commandsOf("agents").filterIsInstance<DrawCmd.Glyph>().firstOrNull())
        val size = assertNotNull(glyph.size as? Extent.World).value
        assertTrue(size < 5.0, "a default glyph must not dominate a 100-unit space; got $size")
        assertTrue(size > 0.0, "but it must still be visible; got $size")
    }

    // ── static skeleton ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theStaticSkeletonShowsEveryElementAtRestWithNoReplayState() {
        val model = ReplayModel.build(AnimationSource(clinicLayout, AnimationTraceHeader(), clinicEvents))
        val scene = SceneBuilder(model).buildStatic()
        // Placement is there...
        assertTrue(scene.commandsOf("queues").any { it is DrawCmd.Polyline }, "the queue's extent is drawn")
        assertTrue(scene.commandsOf("resources").any { it is DrawCmd.Rect }, "the resource is drawn")
        // ...but nothing that depends on the run.
        assertTrue(scene.commandsOf("queues").none { it is DrawCmd.Glyph }, "no queue members at rest")
        assertTrue(scene.commandsOf("entities").isEmpty(), "no entities at rest")
        val cell = assertNotNull(scene.commandsOf("resources").filterIsInstance<DrawCmd.Rect>().firstOrNull())
        assertEquals("#2ca02c", assertNotNull(cell.fill).toHex(), "a resource at rest reads as idle")
    }

    // ── options ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun turningOffTheLegendDropsTheWholeLayer() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0, SceneOptions(showLegend = false))
        assertEquals(null, scene.layer("legend"))
    }

    @Test
    fun emptyLayersAreNotCarriedInTheScene() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        assertTrue(scene.layers.none { it.isEmpty }, "a scene should carry no empty layers")
        assertEquals(scene.layers, scene.nonEmptyLayers())
    }

    @Test
    fun sceneBoundsAndTimeAreReported() {
        val scene = sceneOf(clinicLayout, clinicEvents, 4.0)
        assertEquals(4.0, scene.simTime)
        assertEquals(BoundingBox(0.0, 0.0, 640.0, 380.0), scene.worldBounds)
        assertTrue(scene.commandCount > 0)
    }
}
