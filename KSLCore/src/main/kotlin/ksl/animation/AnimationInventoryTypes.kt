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

package ksl.animation

import kotlinx.serialization.Serializable

/*
 * Conveyor structure descriptions, split out of AnimationInventory so a replay renderer can use them.
 *
 * AnimationInventory walks a built Model by reflection to report what is animatable, which makes it
 * inherently JVM- and modeling-bound. These two descriptions, though, are plain serializable data that
 * the replay layer needs in order to place a conveyed item along its belt -- so they are compiled for a
 * non-JVM target too. Same package, so every existing reference is unchanged.
 */

/** One chained segment of a conveyor (10.5a): its named [entryLocation]→[exitLocation] anchors and cell length. */
@Serializable
data class SegmentInfo(val entryLocation: String, val exitLocation: String, val lengthCells: Int)

/**
 * A conveyor's structure exposed pre-run from the built [ksl.modeling.entity.Conveyor] (10.5a): its [cellSize],
 * whether it is [accumulating], and its ordered, chained [segments]. Lets the editor route the belt against the
 * stations/locations its segments connect before any run.
 */
@Serializable
data class ConveyorInfo(
    val name: String,
    val cellSize: Int,
    val accumulating: Boolean,
    val segments: List<SegmentInfo> = emptyList()
)
