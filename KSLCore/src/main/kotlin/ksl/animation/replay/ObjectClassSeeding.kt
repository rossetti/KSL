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

package ksl.animation.replay

import ksl.animation.AnimationLayout
import ksl.animation.ObjectClassDefinition
import ksl.animation.SpatialSpaceDescriptor

/*
 * Object-class seeding for auto-generated layouts (recovery C1). The renderer sizes an entity/agent glyph by
 * its type's object-class size, defaulting to 10 world units when a type has none — which, on a grid framed to
 * a cell size of 1, paints one agent ~290px wide (the "blob"). Seeding a real, space-scaled, editable
 * object-class per discovered type fixes the scale AND turns appearance into explicit layout data the modeler
 * can recolor/resize/replace, instead of an invisible renderer fallback.
 */

/**
 * A sensible glyph diameter (world units) for objects living in [spaces]: about 0.7 of a grid cell, or a small
 * fraction of a continuous/network space's shorter span. Null when there is no planar space (process-view
 * layouts, where the default size already reads at canvas scale).
 */
fun objectGlyphSize(spaces: List<SpatialSpaceDescriptor>): Double? {
    spaces.filterIsInstance<SpatialSpaceDescriptor.Grid>().firstOrNull()?.let {
        return (0.7 * it.cellSize).coerceAtLeast(0.1)
    }
    spaces.filterIsInstance<SpatialSpaceDescriptor.Continuous>().firstOrNull()?.let {
        val span = minOf(it.xMax - it.xMin, it.yMax - it.yMin)
        if (span > 0.0) return (0.03 * span).coerceAtLeast(0.1)
    }
    spaces.filterIsInstance<SpatialSpaceDescriptor.Network>().firstOrNull()?.let { net ->
        if (net.nodes.isNotEmpty()) {
            val xs = net.nodes.map { it.position.x }; val ys = net.nodes.map { it.position.y }
            val span = minOf(xs.max() - xs.min(), ys.max() - ys.min())
            if (span > 0.0) return (0.03 * span).coerceAtLeast(0.1)
        }
    }
    return null
}

/**
 * Returns a copy of this layout with a default [ObjectClassDefinition] added for every name in [typeNames] that
 * has no object-class yet (existing classes are preserved). Each seeded class gets a palette color, the default
 * circle shape, and [size] (or the model default when null). Idempotent and order-stable (types are sorted), so
 * regenerating a layout reproduces the same colors.
 */
fun AnimationLayout.withSeededObjectClasses(typeNames: Iterable<String>, size: Double?): AnimationLayout {
    val existing = objectClasses.mapTo(HashSet()) { it.typeName }
    val toAdd = typeNames.filter { it.isNotBlank() && it !in existing }.distinct().sorted()
    if (toAdd.isEmpty()) return this
    val seeded = toAdd.mapIndexed { i, t ->
        val color = SEED_PALETTE[i % SEED_PALETTE.size]
        if (size != null) ObjectClassDefinition(typeName = t, color = color, size = size)
        else ObjectClassDefinition(typeName = t, color = color)
    }
    return copy(objectClasses = objectClasses + seeded)
}

/** A categorical palette (matplotlib tab10) for seeded object-class colors. */
private val SEED_PALETTE = listOf(
    "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
    "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
)
