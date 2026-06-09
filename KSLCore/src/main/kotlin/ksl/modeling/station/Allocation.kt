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

package ksl.modeling.station

/**
 *  An opaque token describing one outstanding seize: the [resource], the
 *  [amount] of units held, the simulated [seizeTime] at which the units were
 *  acquired, and the name of the [SeizeStation] that recorded the seize.
 *
 *  Allocations are values, but they are referenced by object identity in the
 *  network's per-entity holding registry — two seizes by the same entity on
 *  the same resource produce two distinct allocations that are released
 *  independently (FIFO by default).
 */
class Allocation internal constructor(
    val resource: SResource,
    val amount: Int,
    val seizeTime: Double,
    val seizeStationName: String
) {
    override fun toString(): String =
        "Allocation(resource=${resource.name}, amount=$amount, seizedAt=$seizeTime, by=$seizeStationName)"
}
