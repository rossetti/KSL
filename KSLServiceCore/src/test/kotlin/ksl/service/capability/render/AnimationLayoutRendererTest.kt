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

package ksl.service.capability.render

import ksl.animation.AnchorRef
import ksl.animation.AnimationLayout
import ksl.animation.BackgroundElement
import ksl.animation.BackgroundKind
import ksl.animation.BarDisplayElement
import ksl.animation.ClockDisplayElement
import ksl.animation.ConveyorLayoutElement
import ksl.animation.ElementKind
import ksl.animation.ElementLabel
import ksl.animation.HistogramDisplayElement
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.LocationLayoutElement
import ksl.animation.MovableResourceLayoutElement
import ksl.animation.NetworkEdge
import ksl.animation.NetworkNode
import ksl.animation.NetworkStationLayoutElement
import ksl.animation.ObjectClassDefinition
import ksl.animation.PathDefinition
import ksl.animation.PlotDisplayElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SegmentRoute
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.StorageLayoutElement
import ksl.animation.SummaryDisplayElement
import ksl.animation.ValueDisplayElement
import ksl.modeling.agent.Cell
import ksl.modeling.agent.GridGeometrySpec
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural checks for the static-preview renderer (Phase 1: skeleton fidelity) — that it draws a non-blank
 * board and that authored element colors are actually painted (parseColor + fills). Also writes preview PNGs
 * under `build/render-preview/` for human/AI visual review against the app's static canvas.
 */
class AnimationLayoutRendererTest {

    private val previewDir: Path = Path.of("build", "render-preview")

    private fun preview(name: String, layout: AnimationLayout) {
        Files.createDirectories(previewDir)
        val p = previewDir.resolve("$name.png")
        AnimationLayoutRenderer.renderToPng(layout, p)
        println("PREVIEW $name -> ${p.toAbsolutePath()}")
    }

    /** True if any pixel exactly matches [rgb] (0xRRGGBB) — solid fills have exact interior pixels. */
    private fun containsRgb(img: BufferedImage, rgb: Int): Boolean {
        val target = rgb and 0xFFFFFF
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if ((img.getRGB(x, y) and 0xFFFFFF) == target) return true
        }
        return false
    }

    @Test
    fun goldenFixtureRendersAllElementsAndAuthoredColors() {
        val layout = golden()
        val img = AnimationLayoutRenderer.renderToImage(layout)
        assertEquals(800, img.width)
        assertEquals(600, img.height)
        // The resource's authored idle color is drawn (proves parseColor + a filled resource glyph).
        assertTrue(containsRgb(img, 0x123456), "the server resource's authored idle color (#123456) should be painted")
        // The object-class legend swatch is drawn (proves the legend + parseColor).
        assertTrue(containsRgb(img, 0xABCDEF), "the Customer object-class legend swatch (#abcdef) should be painted")
        preview("golden", layout)
    }

    @Test
    fun everyElementTypeIsDrawnAtItsPlacedPosition() {
        val layout = golden()
        val img = AnimationLayoutRenderer.renderToImage(layout)
        val at = AnimationLayoutRenderer.screenMapping(layout)
        // Solid resource fills: the exact authored idle color at the placed center.
        assertColorNear(img, at(LayoutPoint(220.0, 160.0)), 0x123456, "resource 'server' idle fill")
        assertColorNear(img, at(LayoutPoint(360.0, 160.0)), 0x2CA02C, "resource 'worker' idle fill")
        // Every other element type drew something at its placed position.
        assertDrawnNear(img, at(LayoutPoint(160.0, 160.0)), "queue head/extent")
        assertDrawnNear(img, at(LayoutPoint(200.0, 300.0)), "storage footprint")
        assertDrawnNear(img, at(LayoutPoint(90.0, 470.0)), "station dot")
        assertDrawnNear(img, at(LayoutPoint(300.0, 470.0)), "location marker + movers")
        assertDrawnNear(img, at(LayoutPoint(385.0, 470.0)), "conveyor belt")
        assertDrawnNear(img, at(LayoutPoint(150.0, 470.0)), "path polyline")
        assertDrawnNear(img, at(LayoutPoint(610.0, 130.0)), "bar frame")
        assertDrawnNear(img, at(LayoutPoint(120.0, 120.0)), "value text")
        assertDrawnNear(img, at(LayoutPoint(400.0, 40.0)), "clock text")
        assertDrawnNear(img, at(LayoutPoint(60.0, 540.0)), "grid obstacle cell")
        assertDrawnNear(img, at(LayoutPoint(600.0, 540.0)), "background line")
        assertDrawnNear(img, at(LayoutPoint(500.0, 560.0)), "background text")
    }

    /** Fails unless some pixel within [radius] px of [p] exactly matches [rgb] (0xRRGGBB). */
    private fun assertColorNear(img: BufferedImage, p: Point2D, rgb: Int, what: String, radius: Int = 8) {
        val target = rgb and 0xFFFFFF
        val cx = p.x.toInt(); val cy = p.y.toInt()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (x in 0 until img.width && y in 0 until img.height && (img.getRGB(x, y) and 0xFFFFFF) == target) return
        }
        fail("$what: expected #%06x within %d px of (%d, %d)".format(target, radius, cx, cy))
    }

    /** Fails unless some non-white pixel exists within [radius] px of [p] (the element drew something there). */
    private fun assertDrawnNear(img: BufferedImage, p: Point2D, what: String, radius: Int = 10) {
        val cx = p.x.toInt(); val cy = p.y.toInt()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (x in 0 until img.width && y in 0 until img.height && (img.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF) return
        }
        fail("$what: expected something drawn within %d px of (%d, %d)".format(radius, cx, cy))
    }

    @Test
    fun malformedColorFallsBackAndDoesNotThrow() {
        val layout = AnimationLayout(
            width = 300.0, height = 200.0,
            resources = listOf(ResourceLayoutElement("r", LayoutPoint(150.0, 100.0), idleColor = "not-a-color")),
        )
        // Should render (gray fallback) without throwing.
        AnimationLayoutRenderer.renderToImage(layout)
    }

    @Test
    fun emptyLayoutRendersBlankBoardWithoutThrowing() {
        val img = AnimationLayoutRenderer.renderToImage(AnimationLayout(width = 400.0, height = 300.0))
        assertEquals(400, img.width)
        assertEquals(300, img.height)
    }

    @Test
    fun realisticFixturesForVisualReview() {
        preview("tandem_movers", tandemWithMovers())
        preview("pharmacy", pharmacy())
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────

    /** A kitchen-sink layout exercising every Phase-1 element type. */
    private fun golden(): AnimationLayout = AnimationLayout(
        title = "Golden Fixture",
        width = 800.0, height = 600.0,
        objectClasses = listOf(
            ObjectClassDefinition("Customer", LayoutShape.CIRCLE, "#abcdef", 12.0),
            ObjectClassDefinition("Cart", LayoutShape.SQUARE, "#ff8800", 14.0),
        ),
        agentStateColors = mapOf("Working" to "#2ca02c", "Idle" to "#cccccc"),
        resources = listOf(
            ResourceLayoutElement("server", LayoutPoint(220.0, 160.0), size = 26.0, idleColor = "#123456"),
            ResourceLayoutElement("worker", LayoutPoint(360.0, 160.0), size = 26.0, idleColor = "#2ca02c", showValue = true),
        ),
        queues = listOf(
            QueueLayoutElement("server:Q", LayoutPoint(160.0, 160.0), growthDegrees = 180.0, spacing = 14.0, maxShown = 8),
            QueueLayoutElement("worker:Q", LayoutPoint(300.0, 160.0), growthDegrees = 180.0, spacing = 14.0, maxShown = 8),
        ),
        storages = listOf(StorageLayoutElement("Oven", LayoutPoint(200.0, 300.0), width = 130.0, height = 50.0, label = "Oven")),
        stations = listOf(
            NetworkStationLayoutElement("Enter", LayoutPoint(90.0, 470.0)),
            NetworkStationLayoutElement("Exit", LayoutPoint(620.0, 470.0)),
        ),
        locations = listOf(
            LocationLayoutElement("Dock", LayoutPoint(300.0, 470.0)),
            LocationLayoutElement("Bay", LayoutPoint(470.0, 470.0)),
            LocationLayoutElement("Unplaced", null), // must be skipped without error
        ),
        paths = listOf(PathDefinition("route", emptyList(), from = AnchorRef.station("Enter"), to = AnchorRef.station("Exit"))),
        movableResources = listOf(
            MovableResourceLayoutElement("AGV1", homeBase = "Dock"),
            MovableResourceLayoutElement("AGV2", homeBase = "Dock"),
            MovableResourceLayoutElement("AGV3", homeBase = "Dock"), // three homed at Dock ⇒ fan-out
        ),
        conveyors = listOf(
            ConveyorLayoutElement("belt", listOf(SegmentRoute("Dock", "Bay")), width = 8.0, color = "#8888ff", label = "Belt"),
        ),
        bars = listOf(BarDisplayElement("util", LayoutPoint(610.0, 130.0), width = 130.0, height = 20.0, label = "Utilization")),
        plots = listOf(PlotDisplayElement("wip", LayoutPoint(610.0, 210.0), width = 150.0, height = 80.0, label = "WIP")),
        histograms = listOf(HistogramDisplayElement("delay", LayoutPoint(610.0, 330.0), width = 150.0, height = 90.0, label = "Delay")),
        values = listOf(ValueDisplayElement("count", LayoutPoint(120.0, 120.0), label = "Count")),
        summaries = listOf(SummaryDisplayElement("tis", LayoutPoint(120.0, 80.0), label = "TimeInSys")),
        clocks = listOf(ClockDisplayElement(LayoutPoint(400.0, 40.0), label = "Time")),
        labels = listOf(ElementLabel(ElementKind.RESOURCE, "server", text = "Server A")), // a name-override
        spaces = listOf(SpatialSpaceDescriptor.Continuous("floor", 0.0, 800.0, 0.0, 600.0)),
        spaceGeometry = listOf(
            GridGeometrySpec("floor", cols = 40, rows = 30, blockedCells = listOf(Cell(2, 26), Cell(3, 26), Cell(2, 27), Cell(3, 27))),
        ),
        background = listOf(
            BackgroundElement(BackgroundKind.TEXT, points = listOf(LayoutPoint(500.0, 560.0)), text = "Zone A", color = "#654321", fontSize = 16.0),
            BackgroundElement(BackgroundKind.LINE, points = listOf(LayoutPoint(440.0, 540.0), LayoutPoint(760.0, 540.0)), color = "#333333", strokeWidth = 2.0),
        ),
    )

    /** A movable-resource tandem: locations + workers + a pool of movers homed at one place (fan-out). */
    private fun tandemWithMovers(): AnimationLayout = AnimationLayout(
        title = "Tandem with Movers",
        width = 1000.0, height = 700.0,
        objectClasses = listOf(ObjectClassDefinition("Customer", LayoutShape.CIRCLE, "#1f77b4", 10.0)),
        queues = listOf(
            QueueLayoutElement("worker1:Q", LayoutPoint(180.0, 80.0), growthDegrees = 180.0),
            QueueLayoutElement("worker2:Q", LayoutPoint(180.0, 150.0), growthDegrees = 180.0),
        ),
        resources = listOf(
            ResourceLayoutElement("worker1", LayoutPoint(240.0, 80.0)),
            ResourceLayoutElement("worker2", LayoutPoint(240.0, 150.0)),
        ),
        locations = listOf(
            LocationLayoutElement("Enter", LayoutPoint(60.0, 250.0), "Enter"),
            LocationLayoutElement("Station1", LayoutPoint(332.0, 250.0), "Station1"),
            LocationLayoutElement("Station2", LayoutPoint(468.0, 250.0), "Station2"),
            LocationLayoutElement("Exit", LayoutPoint(740.0, 250.0), "Exit"),
        ),
        paths = listOf(
            PathDefinition("in", emptyList(), from = AnchorRef.location("Enter"), to = AnchorRef.location("Station1")),
            PathDefinition("mid", emptyList(), from = AnchorRef.location("Station1"), to = AnchorRef.location("Station2")),
            PathDefinition("out", emptyList(), from = AnchorRef.location("Station2"), to = AnchorRef.location("Exit")),
        ),
        movableResources = listOf(
            MovableResourceLayoutElement("Mover1", homeBase = "Station2"),
            MovableResourceLayoutElement("Mover2", homeBase = "Station2"),
            MovableResourceLayoutElement("Mover3", homeBase = "Station2"),
        ),
    )

    /** A simple pharmacy: one queue feeding one server. */
    private fun pharmacy(): AnimationLayout = AnimationLayout(
        title = "Drive-Through Pharmacy",
        width = 600.0, height = 400.0,
        objectClasses = listOf(ObjectClassDefinition("Customer", LayoutShape.CIRCLE, "#1f77b4", 12.0)),
        queues = listOf(QueueLayoutElement("Pharmacy:Q", LayoutPoint(320.0, 180.0), growthDegrees = 180.0, spacing = 14.0)),
        resources = listOf(ResourceLayoutElement("Pharmacist", LayoutPoint(380.0, 180.0), size = 26.0)),
        values = listOf(ValueDisplayElement("NumBusy", LayoutPoint(320.0, 90.0), label = "In Service")),
    )

    // ── Phase 2: spatial context ──────────────────────────────────────────────────────────────────────

    @Test
    fun spatialFixturesForVisualReview() {
        preview("warehouse", warehouse())
        preview("grid_space", gridSpace())
        preview("network_space", networkSpace())
    }

    @Test
    fun obstacleAndSpaceRenderWithoutThrowing() {
        // A continuous space + obstacle grid must render (derives cell size from the space bounds).
        val img = AnimationLayoutRenderer.renderToImage(warehouse())
        assertEquals(620, img.width)
    }

    /** A continuous warehouse floor with grid obstacle bands (racks) + a background text label + chargers. */
    private fun warehouse(): AnimationLayout {
        val blocked = buildList {
            for (row in listOf(8, 9, 18, 19)) for (col in 3..26) if (col !in 14..15) add(Cell(col, row))
        }
        return AnimationLayout(
            title = "Warehouse (AGV)",
            width = 620.0, height = 620.0,
            objectClasses = listOf(ObjectClassDefinition("AGV", LayoutShape.SQUARE, "#8c564b", 1.2)),
            agentStateColors = mapOf("Idle" to "#2ca02c", "Working" to "#ff7f0e", "Charging" to "#d62728"),
            spaces = listOf(SpatialSpaceDescriptor.Continuous("floor", 0.0, 30.0, 0.0, 30.0)),
            spaceGeometry = listOf(GridGeometrySpec("floor", cols = 30, rows = 30, blockedCells = blocked)),
            background = listOf(
                BackgroundElement(
                    BackgroundKind.TEXT, points = listOf(LayoutPoint(1.0, 1.6)),
                    text = "Loading Dock", color = "#333333", fontSize = 1.4,
                ),
            ),
            locations = listOf(
                LocationLayoutElement("Charger1", LayoutPoint(0.5, 4.5), "Charger1"),
                LocationLayoutElement("Charger2", LayoutPoint(0.5, 24.5), "Charger2"),
            ),
        )
    }

    /** A grid space (agent / epidemic style) — grid lines + a class legend. */
    private fun gridSpace(): AnimationLayout = AnimationLayout(
        title = "Grid Space",
        width = 500.0, height = 400.0,
        objectClasses = listOf(
            ObjectClassDefinition("Susceptible", LayoutShape.CIRCLE, "#2ca02c", 0.7),
            ObjectClassDefinition("Infected", LayoutShape.CIRCLE, "#d62728", 0.7),
        ),
        spaces = listOf(SpatialSpaceDescriptor.Grid("grid", cols = 24, rows = 18, cellSize = 1.0)),
    )

    /** A network space — nodes + weighted edges. */
    private fun networkSpace(): AnimationLayout = AnimationLayout(
        title = "Network Space",
        width = 500.0, height = 400.0,
        spaces = listOf(
            SpatialSpaceDescriptor.Network(
                "net",
                nodes = listOf(
                    NetworkNode("A", LayoutPoint(60.0, 70.0)),
                    NetworkNode("B", LayoutPoint(320.0, 90.0)),
                    NetworkNode("C", LayoutPoint(170.0, 300.0)),
                    NetworkNode("D", LayoutPoint(430.0, 300.0)),
                ),
                edges = listOf(
                    NetworkEdge("A", "B"), NetworkEdge("B", "C"), NetworkEdge("C", "A"),
                    NetworkEdge("B", "D"), NetworkEdge("C", "D"),
                ),
            ),
        ),
    )
}
