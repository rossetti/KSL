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

package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.ClockDisplayElement
import ksl.animation.LayoutPoint
import ksl.animation.MovableResourceLayoutElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.StationLayoutElement
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a rough [AnimationLayout] from what a trace contains, so a trace opened with no accompanying
 * layout (the Replay "Quick view") still animates instead of showing a blank canvas. Resources and queues
 * go in two columns with a clock; and — so movement is visible by default (9F.6 / UX U3) — any
 * movable/transport resources in the trace are placed as `movableResource` glyphs, with the named
 * locations they travel between placed on a ring as station anchors (the renderer interpolates the movers
 * between those). Continuous-coordinate movers carry no location names; their `movableResource` glyph is
 * still added and the canvas's content-aware fit frames them.
 *
 * Placement is deliberately crude (the Layout editor is where elements are arranged), and ring positions
 * are approximate — the faithful geometry comes from "Auto-Layout from Model" (which uses the distance
 * matrix). Response statistics are not placed (a model can expose dozens, crushing the auto-grid).
 */
fun ReplayModel.autoLayout(events: List<AnimationEvent>, title: String? = null): AnimationLayout {
    // Movers and the named locations they travel between (name-resolved / DistancesModel), plus conveyor anchors.
    val moves = events.filterIsInstance<AnimationEvent.SpatialElementMoved>()
    val moverNames = moves.map { it.name }.toSortedSet()
    val conveyorAnchors = events.filterIsInstance<AnimationEvent.ConveyorDefined>().flatMap { it.anchorLocations }
    val locationNames = (moves.flatMap { listOfNotNull(it.fromLocationName, it.toLocationName) } + conveyorAnchors).toSortedSet()
    val movers = moverNames.map { MovableResourceLayoutElement(name = it) }

    // Agent state colors from the trace, so agent-state coloring works in Quick view (P5): assign a palette to the
    // distinct states the trace reports (the renderer falls back to the type color for any unmapped state).
    val stateNames = events.filterIsInstance<AnimationEvent.AgentStateEntered>().map { it.stateName }.distinct()
    val agentStateColors = stateNames.mapIndexed { i, s -> s to PALETTE[i % PALETTE.size] }.associate { it }

    // If the trace declares a spatial space (grid/continuous), frame the canvas to it so agents/movers fill the
    // view instead of clumping in a corner (P5). Process elements go in a side strip scaled to the space.
    val spaceBox = effectiveSpaces.mapNotNull { spaceBounds(it) }
        .reduceOrNull { a, b -> a.createUnion(b) as java.awt.geom.Rectangle2D.Double }
    if (spaceBox != null) {
        val margin = (maxOf(spaceBox.width, spaceBox.height) * 0.08).coerceAtLeast(1.0)
        val unit = (spaceBox.height / 16.0).coerceAtLeast(0.5)   // element/glyph size relative to the space
        val rowGap = unit * 1.8
        val resColX = spaceBox.maxX + margin + unit
        val resources = resourceNames.sorted().mapIndexed { i, name ->
            ResourceLayoutElement(resourceName = name, position = LayoutPoint(resColX, spaceBox.y + margin + i * rowGap), size = unit)
        }
        val qColX = resColX + unit * 7
        val queues = queueNames.sorted().mapIndexed { i, name ->
            QueueLayoutElement(queueName = name, position = LayoutPoint(qColX, spaceBox.y + margin + i * rowGap), spacing = unit * 0.7)
        }
        val rightExtent = if (queues.isEmpty() && resources.isEmpty()) spaceBox.maxX + margin else qColX + unit * 7
        return AnimationLayout(
            title = title ?: "Replay",
            width = rightExtent.coerceAtLeast(spaceBox.maxX + margin),
            height = (spaceBox.maxY + margin),
            spaces = effectiveSpaces,
            agentStateColors = agentStateColors,
            resources = resources,
            queues = queues,
            movableResources = movers
        )
    }

    // No spatial space: process-view / name-resolved layout — resources and queues in two columns with a clock,
    // and any named travel locations placed on a ring so name-resolved movement / conveyors animate.
    val originX = 80.0; val originY = 80.0; val rowGap = 70.0; val columnGap = 240.0
    val resColX = originX + 160.0
    val resources = resourceNames.sorted().mapIndexed { i, name ->
        ResourceLayoutElement(resourceName = name, position = LayoutPoint(resColX, originY + i * rowGap))
    }
    val qColX = resColX + columnGap
    val queues = queueNames.sorted().mapIndexed { i, name ->
        QueueLayoutElement(queueName = name, position = LayoutPoint(qColX, originY + i * rowGap))
    }
    val rows = maxOf(resources.size, queues.size, 1)
    val height = (originY + rows * rowGap + 80.0).coerceAtLeast(400.0)
    val width = (qColX + 320.0).coerceAtLeast(800.0)
    val stations = if (locationNames.isEmpty()) emptyList() else {
        val cx = width / 2.0; val cy = height / 2.0
        val radius = minOf(width, height) * 0.32
        locationNames.toList().mapIndexed { i, name ->
            val a = 2.0 * Math.PI * i / locationNames.size - Math.PI / 2.0 // first location at top
            StationLayoutElement(stationName = name, position = LayoutPoint(cx + radius * cos(a), cy + radius * sin(a)), label = name)
        }
    }
    return AnimationLayout(
        title = title ?: "Replay",
        width = width,
        height = height,
        agentStateColors = agentStateColors,
        clocks = listOf(ClockDisplayElement(position = LayoutPoint(originX, originY / 2))),
        resources = resources,
        queues = queues,
        stations = stations,
        movableResources = movers
    )
}

/** A standard categorical palette for assigning colors to agent states discovered in a trace (P5). */
private val PALETTE = listOf(
    "#1f77b4", "#d62728", "#2ca02c", "#ff7f0e", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
)

/** The world bounding box of a spatial space descriptor (grid/continuous); null for spaces without planar bounds. */
private fun spaceBounds(s: ksl.animation.SpatialSpaceDescriptor): java.awt.geom.Rectangle2D.Double? = when (s) {
    is ksl.animation.SpatialSpaceDescriptor.Grid ->
        java.awt.geom.Rectangle2D.Double(s.originX, s.originY, s.cols * s.cellSize, s.rows * s.cellSize)
    is ksl.animation.SpatialSpaceDescriptor.Continuous ->
        java.awt.geom.Rectangle2D.Double(s.xMin, s.yMin, s.xMax - s.xMin, s.yMax - s.yMin)
    is ksl.animation.SpatialSpaceDescriptor.Network -> if (s.nodes.isEmpty()) null else {
        val xs = s.nodes.map { it.position.x }; val ys = s.nodes.map { it.position.y }
        java.awt.geom.Rectangle2D.Double(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }
    else -> null
}
