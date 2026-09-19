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
package ksl.modeling.fleet

/**
 * What one ride cost the load that took it.
 *
 * The counterpart of [FleetTransportResult] for a load that boarded rather than posted, and it
 * decomposes the same way so that the two are comparable: a wait, then a ride. There is no
 * `waitForAssignment` term, and its absence is the whole difference between the two paradigms --
 * nobody decided that a vehicle would come, so there is no interval during which that decision was
 * pending.
 *
 * @param totalTime joining the line at the origin stop until being set down
 * @param waitForVehicle joining the line until aboard. At an interchange this **is** the connection
 *   time, which is why a transfer needs no machinery of its own: a rider whose process rides, then
 *   rides again, reports the connection as the second ride's wait
 * @param timeAboard aboard until set down
 * @param origin the stop it boarded at
 * @param destination where it was set down
 * @param vehicleName which vehicle carried it
 * @param numVehiclesPassed how many vehicles served the origin stop while it waited and left it
 *   standing, for want of room. The per-rider counterpart of the stop's `NumPassedByFull`, and the
 *   thing a capacity study is usually about
 */
data class TransitResult(
    val totalTime: Double,
    val waitForVehicle: Double,
    val timeAboard: Double,
    val origin: String,
    val destination: String,
    val vehicleName: String,
    val numVehiclesPassed: Int
) {
    override fun toString(): String =
        "TransitResult($origin -> $destination, total=$totalTime, wait=$waitForVehicle, " +
                "aboard=$timeAboard, vehicle=$vehicleName, passedBy=$numVehiclesPassed)"
}
