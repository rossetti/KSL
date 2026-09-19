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
package ksl.modeling.guidedpath.internal

import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.LinkZone
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.exceptions.DeadlockParticipant
import ksl.modeling.guidedpath.exceptions.DeadlockReport
import ksl.modeling.guidedpath.exceptions.IdleTransporterObstruction
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Decides whether a transporter that has just stopped is in a circular wait, or merely behind
 * something that will never move on its own.
 *
 * The two look identical from inside a run -- the clock advances and nobody goes anywhere -- and
 * they call for opposite responses. A cycle cannot resolve itself, so it ends the replication and
 * says who was in it. An obstruction can resolve itself the moment something dispatches the idle
 * transporter, so it is reported and counted and the run goes on. Telling them apart is the point
 * of this class, and correctly classifying the second is the thing the reference tool cannot do at
 * all.
 *
 * **This class holds nothing and changes nothing.** The wait-for graph is walked from the
 * transporter that has just blocked and then discarded, and no zone, transporter, link, or route is
 * touched. That is not tidiness either: detection is optional, so a detector that could mutate
 * would make a run with detection enabled differ from the same run without it, and the two would no
 * longer be the same experiment. `DetectorPurityTest` holds that line.
 *
 * @param system the runtime whose fleet is being examined
 */
internal class DeadlockDetector(private val system: GuidedPathSpace) {

    /**
     * The transporters standing between this one and where it wants to go.
     *
     * There are four ways to be held up and each resolves to a different set. Waiting for a zone
     * is the simple case: whoever holds the zone. Waiting for a spur means waiting for the one
     * transporter that reserved it. Waiting for a link's direction is the case with more than one
     * answer -- a link runs one way at a time and any number of transporters may be running it, so
     * the direction comes free only when the last of them leaves, and every one of them is
     * therefore in the way.
     *
     * The fourth is waiting for a zone that is **free**, and refused because it is *reserved*. The
     * design record originally argued this case away: all-or-nothing acquisition keeps a
     * non-vehicle holder from holding anything while it waits, and a holder that holds nothing
     * cannot be what a vehicle is waiting for. That reasoning conflated having no
     * [ksl.modeling.guidedpath.ZoneHolderIfc.awaitedZone] with having no outgoing edge. A closure
     * that is still pending waits on **every vehicle occupying the zones it has reserved**, and the
     * reservation is what the refused vehicle is waiting on -- two edges, in opposite directions,
     * neither of which all-or-nothing removes. Two closures reserving adjacent regions can each
     * trap a vehicle in the other's way, and then neither region drains.
     *
     * The closure is collapsed out rather than added as a node: a vehicle refused by a reservation
     * is waiting, transitively, on whichever vehicles are keeping that reservation from being
     * granted. That keeps the walk and the cycle report about vehicles, which is what a modeller
     * can act on, while `awaitedZoneReservedFor` on the report says which closure made the edge.
     */
    private fun obstructorsOf(transporter: GuidedTransporter): List<GuidedTransporter> {
        val link = transporter.awaitedLink
        if (link != null) {
            val reservation = link.spurReservation
            if (reservation != null && reservation !== transporter) return listOf(reservation)
            return system.transporters.filter { other ->
                other !== transporter &&
                        other.heldZones.any { it is LinkZone && it.link === link }
            }
        }
        val awaited = transporter.awaitedZone ?: return emptyList()
        val holder = awaited.holder
        if (holder == null) {
            // Free, and refused: the zone is reserved for a closure that has not been granted yet.
            // What stands in the way is whatever is stopping that closure from being granted, which
            // is the vehicles occupying its other zones. A closure occupying none of them yet is
            // not itself an obstacle and contributes nothing.
            val closing = awaited.closure ?: return emptyList()
            return closing.zones
                .mapNotNull { it.holder as? GuidedTransporter }
                .filter { it !== transporter }
                .distinct()
        }
        // A holder that is not a vehicle is holding the zone outright, and a vehicle behind it is
        // obstructed rather than deadlocked: the hold ends on its own, by a clock or by whatever
        // process took it, so no cycle runs through it. That is still true -- what was not true is
        // the same claim about a closure that is merely *pending*, which the branch above handles.
        if (holder !is GuidedTransporter) return emptyList()
        return if (holder === transporter) emptyList() else listOf(holder)
    }

    /**
     * Walks the wait-for graph from a transporter that has just blocked, looking for a way back to
     * something already on the path.
     *
     * Only blocked transporters are followed. One that is still moving will release what it holds
     * without anyone's help, so no cycle can run through it; one standing idle is an obstruction
     * rather than a participant, since it is not waiting for anything and so has no outgoing edge
     * to close a cycle with. Restricting the walk this way is what keeps the two conditions from
     * being mistaken for each other.
     *
     * @param start the transporter that has just become blocked
     * @return the cycle it lies on, or null when there is none
     */
    fun findCycle(start: GuidedTransporter): DeadlockReport? {
        if (start.transporterState != TransporterState.BLOCKED) return null
        val path = mutableListOf<GuidedTransporter>()
        // Identity sets throughout: transporters are compared by reference everywhere else in the
        // subsystem, and two distinct ones must never be conflated here.
        val onPath: MutableSet<GuidedTransporter> =
            Collections.newSetFromMap(IdentityHashMap())
        val settled: MutableSet<GuidedTransporter> =
            Collections.newSetFromMap(IdentityHashMap())
        var cycle: List<GuidedTransporter>? = null

        fun walk(transporter: GuidedTransporter): Boolean {
            path.add(transporter)
            onPath.add(transporter)
            for (next in obstructorsOf(transporter)) {
                if (next.transporterState != TransporterState.BLOCKED) continue
                if (onPath.contains(next)) {
                    cycle = path.subList(path.indexOfFirst { it === next }, path.size).toList()
                    return true
                }
                if (settled.contains(next)) continue
                if (walk(next)) return true
            }
            path.removeAt(path.size - 1)
            onPath.remove(transporter)
            settled.add(transporter)
            return false
        }

        if (!walk(start)) return null
        val found = cycle ?: return null
        return DeadlockReport(
            time = system.time,
            participants = found.map { t ->
                DeadlockParticipant(
                    transporterName = t.name,
                    heldZoneNames = t.heldZones.map { it.name },
                    awaitedZoneName = awaitedName(t),
                    awaitedZoneReservedFor = awaitedReservation(t)
                )
            }
        )
    }

    /**
     * Whether this transporter is stopped behind one that has nothing to do and nothing scheduled.
     *
     * An idle, unallocated transporter will not move on its own: no entity holds it, no journey is
     * under way, and nothing will wake it. A transporter waiting on space it holds is therefore
     * waiting indefinitely, and the replication will finish looking like a system that merely had
     * no work rather than one that stopped.
     *
     * This is a judgement about one instant and it can be overtaken by events: an entity may seize
     * the idle transporter a moment later and the obstruction clears itself. That is exactly why
     * the default response is to warn and count rather than to raise. The reading is worth having
     * and is not certain enough to end a run on. A model that wants certainty sets the strict
     * policy and takes the false alarms with it.
     *
     * @param start the transporter that has just become blocked
     * @return what is in its way, or null when whatever holds the space is going somewhere
     */
    fun findObstruction(start: GuidedTransporter): IdleTransporterObstruction? {
        if (start.transporterState != TransporterState.BLOCKED) return null
        val idle = obstructorsOf(start).firstOrNull { it.isPermanentlyStationary } ?: return null
        return IdleTransporterObstruction(
            time = system.time,
            blockedTransporterName = start.name,
            awaitedZoneName = awaitedName(start),
            idleTransporterName = idle.name
        )
    }


    /**
     * What a blocked transporter is waiting for, named. A transporter held up by a link is waiting
     * for a specific zone on it, so the zone is the more useful name of the two and is preferred.
     */
    private fun awaitedName(transporter: GuidedTransporter): String =
        transporter.awaitedZone?.name ?: transporter.awaitedLink?.name ?: "nothing"

    /**
     * Whom the awaited zone is reserved for, when it is free and a reservation is what refuses it.
     *
     * Null in the ordinary case, where something is actually standing in the zone. A zone that is
     * both held and reserved reads as held, because that is what the waiting vehicle is up against
     * first.
     */
    private fun awaitedReservation(transporter: GuidedTransporter): String? {
        val awaited = transporter.awaitedZone ?: return null
        if (awaited.holder != null) return null
        return awaited.closingFor?.name
    }
}
