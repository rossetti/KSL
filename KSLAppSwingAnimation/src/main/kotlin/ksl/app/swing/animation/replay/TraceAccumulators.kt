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
import ksl.animation.LayoutPoint
import ksl.animation.MoverMode
import java.awt.geom.Rectangle2D

/**
 * The named locations seen in a trace ([names], including non-Cartesian ones and conveyor anchors) and
 * the centroid of every location that reported FINITE coordinates ([centroids]).
 */
data class LocationSummary(val names: Set<String>, val centroids: Map<String, LayoutPoint>)

/**
 * The movers seen in a trace ([names]) and the at-rest position of every mover that reported FINITE
 * coordinates ([homes]).
 */
data class MoverSummary(val names: Set<String>, val homes: Map<String, LayoutPoint>)

/**
 * The world bounding box over the FINITE coordinates carried by `SpatialElementMoved` events. Non-Cartesian
 * spatial models (distance-, network-, great-circle-based) emit `NaN` coordinates (see `LocationIfc.x`), so
 * those are skipped and [result] is null when the trace carries no planar coordinate at all.
 */
class ObservedExtent : TraceAccumulator<Rectangle2D.Double?> {
    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY
    private var any = false

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.SpatialElementMoved) {
            include(event.fromX, event.fromY)
            include(event.toX, event.toY)
        }
    }

    private fun include(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) return
        any = true
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }

    override fun result(): Rectangle2D.Double? =
        if (!any) null else Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)
}

/**
 * Collects every named travel location (from `SpatialElementMoved` location names and `ConveyorDefined`
 * anchors) and the running centroid of each one that reported FINITE coordinates. Names with only
 * non-Cartesian (`NaN`) coordinates are still listed but have no centroid, so the caller falls back to a
 * crude placement for them.
 */
class LocationCentroids : TraceAccumulator<LocationSummary> {
    private class Mean { var sx = 0.0; var sy = 0.0; var n = 0 }

    private val names = LinkedHashSet<String>()
    private val acc = LinkedHashMap<String, Mean>()

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.SpatialElementMoved -> {
                add(event.fromLocationName, event.fromX, event.fromY)
                add(event.toLocationName, event.toX, event.toY)
            }
            is AnimationEvent.ConveyorDefined -> names.addAll(event.anchorLocations)
            else -> {}
        }
    }

    private fun add(name: String?, x: Double, y: Double) {
        if (name == null) return
        names.add(name)
        if (x.isFinite() && y.isFinite()) {
            val m = acc.getOrPut(name) { Mean() }
            m.sx += x; m.sy += y; m.n++
        }
    }

    override fun result(): LocationSummary =
        LocationSummary(names, acc.mapValues { (_, m) -> LayoutPoint(m.sx / m.n, m.sy / m.n) })
}

/**
 * Collects every mover seen and its at-rest position: the destination of a `RETURNING_HOME` move if the
 * trace shows one, otherwise the mover's first finite position. Movers with only non-Cartesian (`NaN`)
 * coordinates are still listed but have no home, so they animate by name/path without a seeded glyph spot.
 */
class MoverHomes : TraceAccumulator<MoverSummary> {
    private val names = LinkedHashSet<String>()
    private val first = LinkedHashMap<String, LayoutPoint>()
    private val home = LinkedHashMap<String, LayoutPoint>()

    override fun accept(event: AnimationEvent) {
        if (event !is AnimationEvent.SpatialElementMoved) return
        names.add(event.name)
        if (event.fromX.isFinite() && event.fromY.isFinite()) {
            first.putIfAbsent(event.name, LayoutPoint(event.fromX, event.fromY))
        }
        if (event.mode == MoverMode.RETURNING_HOME && event.toX.isFinite() && event.toY.isFinite()) {
            home[event.name] = LayoutPoint(event.toX, event.toY)
        }
    }

    override fun result(): MoverSummary =
        MoverSummary(names, names.mapNotNull { n -> (home[n] ?: first[n])?.let { n to it } }.toMap())
}

/** The distinct agent states a trace reports, in first-seen order, for assigning the color palette. */
class AgentStateNames : TraceAccumulator<List<String>> {
    private val states = LinkedHashSet<String>()

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.AgentStateEntered) states.add(event.stateName)
    }

    override fun result(): List<String> = states.toList()
}
