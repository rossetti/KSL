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
package ksl.modeling.guidedpath.exceptions

/**
 * One transporter's part in a circular wait: what it holds, and what it is waiting for.
 *
 * Participants are identified by name rather than by object reference so that a report can be
 * logged, serialized, compared in a test, or held after the replication that produced it has
 * ended, without keeping any simulation state alive.
 *
 * @param transporterName the blocked transporter
 * @param heldZoneNames the zones it occupies or has claimed, ordered rear to front
 * @param awaitedZoneName the zone it cannot claim, which the next participant in the cycle holds
 * @param awaitedZoneReservedFor the holder the awaited zone is reserved for, when it is **free**
 *   and what refuses it is a reservation rather than an occupant. Naming it is the difference
 *   between a usable report and a misleading one: without it a modeller reads two vehicles awaiting
 *   two zones, goes looking for a head-on vehicle conflict, and finds the zones empty.
 */
data class DeadlockParticipant(
    val transporterName: String,
    val heldZoneNames: List<String>,
    val awaitedZoneName: String,
    val awaitedZoneReservedFor: String? = null
) {
    override fun toString(): String = buildString {
        append("$transporterName holds [${heldZoneNames.joinToString()}] and awaits $awaitedZoneName")
        if (awaitedZoneReservedFor != null) {
            append(", which is free but reserved for $awaitedZoneReservedFor")
        }
    }
}

/**
 * A circular wait among transporters, as found in the wait-for graph at a single instant.
 *
 * The participants are in cycle order: each one awaits a zone held by the next, and the last
 * awaits a zone held by the first. A report is a value, produced when a cycle is detected and not
 * retained by the subsystem afterwards, so it is safe to keep and to assert against in a test.
 *
 * @param time the simulation time at which the cycle was detected
 * @param participants the transporters in the cycle, in cycle order, at least two of them
 */
data class DeadlockReport(
    val time: Double,
    val participants: List<DeadlockParticipant>
) {
    init {
        require(participants.size >= 2) {
            "A deadlock cycle requires at least two participants, but ${participants.size} were given."
        }
    }

    /**
     * A multi-line rendering naming every participant, used as the exception message and written
     * to the log at the point of detection.
     */
    override fun toString(): String = buildString {
        append("Deadlock detected at time $time among ${participants.size} transporters:")
        for (p in participants) {
            append(System.lineSeparator())
            append("  ")
            append(p)
        }
        append(System.lineSeparator())
        val throughAReservation = participants.any { it.awaitedZoneReservedFor != null }
        append(
            // "held by the next" is the ordinary case and is what the great majority of these
            // reports describe. It would be false of a cycle running through a reservation, where
            // the awaited zones are empty, so that case says so instead rather than being
            // contradicted by the paragraph below it.
            if (throughAReservation) {
                "Each transporter awaits a zone the next one stands in the way of, so none can " +
                        "move. See the guide on designing deadlock out: prefer unidirectional " +
                        "links, use spurs for dead ends, and send idle transporters to a staging " +
                        "area."
            } else {
                "Each transporter awaits a zone held by the next, so none can move. See the guide " +
                        "on designing deadlock out: prefer unidirectional links, use spurs for " +
                        "dead ends, and send idle transporters to a staging area."
            }
        )
        if (throughAReservation) {
            append(System.lineSeparator())
            append(
                "Some of these zones are free and reserved rather than occupied. A closure that " +
                        "is still waiting for its space admits only a vehicle already holding one " +
                        "of its own zones, so two closures reserving adjacent regions can each " +
                        "trap a vehicle in the other's way -- and then neither region ever drains. " +
                        "Ask for regions that do not abut, or ask for them as one closure."
            )
        }
    }
}

/**
 * Thrown when transporters have formed a circular wait and the run cannot continue.
 *
 * This is a domain outcome, not a defect. The model is valid and the answer is that this
 * configuration deadlocks, which is often the finding a study is after. A run that reaches this
 * state is aborted rather than repaired, because any automatic repair would silently change the
 * system being modeled.
 *
 * Detection runs when a transporter becomes blocked, which is the only moment at which a cycle can
 * form, so the exception is raised at the instant the deadlock comes into existence rather than
 * after the run has quietly stopped advancing.
 *
 * A user sweeping a parameter region that contains deadlock should catch this around the
 * replication and record the design point as infeasible.
 *
 * @param report the participants in the cycle and the time it formed
 */
class GuidedPathDeadlockException(
    val report: DeadlockReport
) : RuntimeException(report.toString())

/**
 * A transporter blocked behind another that occupies space and has nothing scheduled to make it
 * move: an idle, unallocated transporter parked on the path, and a second transporter that needs
 * the space it holds.
 *
 * This is a reading of one instant, and what happens next is no part of it. Most of these clear:
 * the next piece of work seizes the parked transporter, it drives off, and the blocked one goes
 * on. One of them is a journey that had to wait, and a busy shop can produce dozens in a run that
 * delivers everything it was given.
 *
 * It lasts to the end of the replication exactly when nothing arrives to move the parked
 * transporter, which is the case worth alarm, and the replication-end audit is what reports it by
 * naming every transporter still waiting when the horizon fell. A count that rises while the fleet
 * keeps delivering is congestion; a run that ends with transporters still blocked is a fleet that
 * stopped.
 *
 * There is no cycle here, so it is not deadlock, and moving the idle transporter clears it, which
 * is why it is reported rather than thrown by default. It is counted as well as logged, so that it
 * appears in the standard report where an analyst will see it rather than only in a log. Setting
 * the transport system's strict obstruction policy promotes it to `GuidedPathObstructionException`
 * instead.
 *
 * @param time the simulation time at which the obstruction was observed
 * @param blockedTransporterName the transporter that cannot proceed
 * @param awaitedZoneName the zone it needs
 * @param idleTransporterName the idle transporter holding that zone
 */
data class IdleTransporterObstruction(
    val time: Double,
    val blockedTransporterName: String,
    val awaitedZoneName: String,
    val idleTransporterName: String
) {
    override fun toString(): String =
        "At time $time, transporter ($blockedTransporterName) is blocked on zone " +
                "($awaitedZoneName), which is held by idle transporter ($idleTransporterName). " +
                "Nothing is scheduled to move the idle transporter, so this wait lasts until " +
                "something dispatches it, and lasts to the end of the replication if nothing " +
                "does. This is not a circular wait. Give idle transporters a home base or a " +
                "staging area so that they leave the path."
}

/**
 * Thrown in place of a warning when the transport system's strict obstruction policy is set.
 *
 * @param obstruction the transporters and zone involved
 */
class GuidedPathObstructionException(
    val obstruction: IdleTransporterObstruction
) : RuntimeException(obstruction.toString())
