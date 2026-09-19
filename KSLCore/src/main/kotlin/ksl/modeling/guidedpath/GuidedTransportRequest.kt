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
package ksl.modeling.guidedpath

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.ProcessModel

/**
 * What happened during one journey.
 *
 * Returned to the process rather than only accumulated into statistics, because the entity is
 * suspended for the whole journey and has no other way to learn what became of it. Time spent
 * unable to claim the space ahead is the quantity of interest: it is what a free-path model cannot
 * produce at all, and what says whether a fleet is getting in its own way.
 *
 * @param totalTime the whole journey, from the request to arrival at the destination
 * @param approachTime from the transporter being committed until the entity is aboard, excluding
 *   the loading delay. A protocol interval: whether the transporter was empty during it is a
 *   separate question, and one the transporter's own `fracTimeMovingEmpty` answers.
 * @param rideTime from the entity being aboard until it is set down, excluding the unloading delay
 * @param blockedTime time within the journey spent unable to claim the space ahead
 * @param zonesTraversed how many zones the transporter crossed while carrying the entity
 * @param routeLength the distance covered while carrying the entity
 */
data class GuidedTransportResult(
    val totalTime: Double,
    val approachTime: Double,
    val rideTime: Double,
    val blockedTime: Double,
    val zonesTraversed: Int,
    val routeLength: Double
) {
    override fun toString(): String =
        "GuidedTransportResult(total=$totalTime, approach=$approachTime, ride=$rideTime, " +
                "blocked=$blockedTime, zones=$zonesTraversed, distance=$routeLength)"
}

/** Where a journey has got to. */
enum class GuidedTransportRequestState {

    /** A transporter has been allocated and is either coming or waiting to be told where to go. */
    ALLOCATED,

    /** The transporter has been released and the request can do nothing further. */
    COMPLETED
}

/**
 * An entity's claim on a transporter: obtained when one is allocated, used to command the journey,
 * and given up at the end.
 *
 * A request goes inert the moment the transporter is released. Using it afterwards is a mistake in
 * the process rather than a situation to accommodate -- most often a second release, or a journey
 * commanded with a transporter that now belongs to someone else -- and it is far cheaper to find
 * as an immediate, clearly attributed failure than as a transporter that turns out to be in two
 * places at once.
 *
 * @param transporter the transporter allocated to the entity
 * @param entity the entity it is allocated to
 * @param pool the pool the transporter came from, and the passive transport system it belongs to.
 *   Carried because a transporter knows only the *space* it moves through: `transporter.system` is
 *   a [GuidedPathSpace], shared with the active subsystem, and the request-to-set-down total is
 *   this paradigm's own figure. Threading it here keeps `transportBy` usable with nothing but a
 *   request, as its signature promises.
 * @param allocation the underlying resource allocation, released at the end
 * @param timeAllocated when the transporter was allocated
 */
class GuidedTransportRequest internal constructor(
    val transporter: GuidedTransporter,
    val entity: ProcessModel.Entity,
    internal val pool: GuidedTransporterPoolWithQ,
    internal val allocation: Allocation,
    val timeAllocated: Double
) {

    /** Where the journey has got to. */
    var state: GuidedTransportRequestState = GuidedTransportRequestState.ALLOCATED
        internal set

    /** True once the transporter has been released. */
    val isCompleted: Boolean
        get() = state == GuidedTransportRequestState.COMPLETED

    internal var approachTime: Double = 0.0
    internal var rideTime: Double = 0.0
    internal var zonesTraversed: Int = 0
    internal var routeLength: Double = 0.0
    /** When the entity asked for a transporter, which precedes the allocation by the pool wait. */
    internal var requestedAt: Double = timeAllocated

    /** The transporter's blocked-time total at allocation, so the journey's own share can be told. */
    internal var blockedAtAllocation: Double = 0.0

    /**
     * Checks that this request may still be used, and says what went wrong when it may not.
     *
     * @throws IllegalStateException when the transporter has already been released
     */
    internal fun requireUsable(what: String) {
        check(!isCompleted) {
            "Cannot $what: the request for transporter (${transporter.name}) held by entity " +
                    "(${entity.name}) was completed when the transporter was released. A request " +
                    "cannot be used again once its transporter has gone back to the pool."
        }
    }

    override fun toString(): String =
        "GuidedTransportRequest(transporter=${transporter.name}, entity=${entity.name}, state=$state)"
}
