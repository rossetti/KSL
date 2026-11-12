/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.entity

/**
 *  How a resource's behavior stops when `Resource.stopBehavior` is called.
 */
enum class BehaviorStop {
    /**
     *  The current cycle of a cyclic behavior finishes and no other starts. A behavior declared with
     *  `behavior` has no cycles, so for it this is the same as `NOW`.
     */
    AFTER_CYCLE,

    /**
     *  The behavior ends at once: its process is terminated, releasing what it holds, with its
     *  `finally` blocks running.
     */
    NOW
}
