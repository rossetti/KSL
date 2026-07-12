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

package ksl.app.animation.replay

import ksl.animation.AnimationLayout
import ksl.modeling.agent.GridGeometrySpec

/**
 * Stamps faithful static space geometry (obstacle maps / grid-graph costs) onto a layout. A trace carries
 * space *descriptors* but no obstacle geometry, so this brings it from the model inventory. Matched by space
 * name: geometry is added only for a space the layout actually has and doesn't already carry geometry for —
 * so it is idempotent and never overrides geometry the layout already defines.
 */
fun AnimationLayout.withSpaceGeometry(geometry: List<GridGeometrySpec>): AnimationLayout {
    if (geometry.isEmpty()) return this
    val present = spaces.map { it.name }.toSet()
    val have = spaceGeometry.map { it.spaceName }.toSet()
    val added = geometry.filter { it.spaceName in present && it.spaceName !in have }
    return if (added.isEmpty()) this else copy(spaceGeometry = spaceGeometry + added)
}
