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
 *  What happens to work in service when units of the resource it holds are taken away, by a failure
 *  or by a more urgent request. The holder is not told; its delay is held or not, and its `release`
 *  works as before.
 */
enum class InterruptRule {
    /** Nothing is taken from the holder: its work completes, and the units are taken at its release. */
    FINISH,

    /** The holder's delay is held while the units are away, and goes on with the time left when they return. */
    RESUME,

    /** The holder's delay is held while the units are away, and starts again in full when they return. */
    RESTART
}
