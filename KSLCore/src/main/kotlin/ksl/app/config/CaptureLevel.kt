/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.config

import kotlinx.serialization.Serializable

/**
 * Controls the volume of animation events captured during a run.
 *
 * This is a placeholder enum whose full taxonomy will be designed in Phase 7,
 * after the observability inventory of `ksl.modeling` element classes.  It is
 * defined here so [TracingConfig] (and therefore [RunConfiguration]) compiles in
 * Phase 2 without forward-referencing a package that does not yet exist.
 */
@Serializable
enum class CaptureLevel {
    /** Only coarse-grained state changes: queue length deltas, resource state transitions. */
    MINIMAL,
    /** Adds entity creation, entity destruction, and spatial-movement events. */
    STANDARD,
    /** All observable events, including fine-grained sim-time ticks. */
    FULL
}
