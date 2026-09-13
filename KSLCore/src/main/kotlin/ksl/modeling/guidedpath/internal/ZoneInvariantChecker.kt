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
import ksl.modeling.guidedpath.IntersectionZone
import ksl.modeling.guidedpath.LinkZone
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.ZoneHolderIfc
import ksl.modeling.guidedpath.ZoneState

/**
 * Raised when the guide path has reached a state that should be impossible.
 *
 * This is always a defect in the subsystem, never a modelling error, so the message says what was
 * violated and shows the state that violated it rather than advising the modeler to change
 * anything.
 */
class ZoneInvariantViolation(message: String) : IllegalStateException(message)

/**
 * Checks that no transporter shares space with another, that each covers an unbroken run of zones no
 * longer than itself, and that what the zones believe agrees with what the transporters believe.
 *
 * These are the properties the whole subsystem exists to guarantee, and they are exactly the ones
 * that a plausible-looking run can violate without any visible symptom: a model whose transporters
 * quietly pass through one another produces output that looks entirely reasonable and is wrong.
 * Asserting them continuously is what turns "we believe transporters never overlap" into something
 * the test suite demonstrates on every model it runs.
 *
 * **What it does not assert is a state part way through an instant**, and that is a real constraint
 * rather than a convenience. Several events execute at one instant, and the hand-off that frees a
 * zone spans two of them: the release takes the woken transporter off the zone's waiting list and
 * schedules it a zero-delay retry, so between those two events it is blocked and waiting in no list
 * at all. That is sound, and asserting over it would report a defect on a correct run. What has to
 * hold is that the state is sound once an instant has finished, which is what [check] is given.
 *
 * The class holds no state and is driven entirely by its caller. [GuidedPathSpace] decides when an
 * instant has finished -- it knows, because nothing can change a zone or a transporter except
 * through the handful of places it owns -- and the audit does not go looking.
 */
internal class ZoneInvariantChecker(
    private val mySystem: GuidedPathSpace
) {

    /**
     * The instant whose finished state is being asserted about, for the message. NaN while no audit
     * is under way.
     */
    private var myAuditedTime: Double = Double.NaN

    /**
     * Runs every check, throwing on the first violation found.
     *
     * @param atTime the instant whose finished state this is, which is generally earlier than the
     *   clock now reads. A violation names both, because the instant that created it is the one a
     *   reader needs and the instant it was found in is the one the trace will show.
     */
    fun check(atTime: Double) {
        myAuditedTime = atTime
        try {
            checkZonesAgreeWithTransporters()
            checkTransportersCoverContiguousRuns()
            checkCoverageIsConserved()
            checkWaitingIsConsistent()
        } finally {
            myAuditedTime = Double.NaN
        }
    }

    /**
     * Runs at the end of a replication, whether or not continuous checking was on.
     *
     * Everything [check] asserts is asserted here too, which is the point: a model that did not ask
     * for the continuous walk still gets one look at its own state, at the moment there is most to
     * find and least left to pay. It costs one pass over the guide path per replication.
     *
     * What it adds is the exhaustive waiter sweep that [checkWaitingIsConsistent] leaves out,
     * because searching the whole network for stray waiters is affordable once and not continuously.
     */
    fun checkClosing(unauditedInstant: Double) {
        check(if (unauditedInstant.isFinite()) unauditedInstant else mySystem.time)
        myAuditedTime = mySystem.time
        try {
            checkNoStrayWaiters()
        } finally {
            myAuditedTime = Double.NaN
        }
    }

    /**
     * A transporter is waiting for exactly one thing, and only while it is stopped.
     *
     * This is the invariant whose failure has no other symptom. A waiting transporter schedules
     * nothing, so it is started again only by whoever releases what it is waiting for -- which
     * means a transporter left in a waiting list it should have left, or missing from the one it
     * should be in, does not recover slowly. It stalls forever, and the run simply stops advancing
     * with nothing to say why.
     *
     * Asked of the transporters, not of the network. Each one records what it is waiting for, so
     * the question "is it in the list it names" is answered by looking in one list; the old form
     * searched every zone and every link for each transporter and allocated two lists per
     * transporter doing it, which on the reference configuration was over nine thousand collection
     * probes per audit and **ninety-one per cent of the whole audit's cost**.
     *
     * The reverse direction -- that no *other* list holds it -- is not searched for here, and
     * deliberately. A transporter enters a waiting list only through `blockOnZone` or `blockOnLink`,
     * each of which records what it awaits in the same breath, and leaves through `cancelWait` or
     * the release that wakes it. Three functions in one file maintain it. [checkClosing] does the
     * exhaustive sweep once per replication, where searching the whole network costs nothing.
     */
    private fun checkWaitingIsConsistent() {
        for (t in mySystem.transporters) {
            if (t.transporterState != TransporterState.BLOCKED) continue
            val zone = t.awaitedZone
            if (zone == null) {
                violate("transporter (${t.name}) is blocked but names nothing it is waiting for")
            }
            // A transporter held up by a link queues on the link, not on the zone beyond it, so
            // which list to look in is decided by which of the two it named.
            val link = t.awaitedLink
            val named = if (link != null) link.name else zone.name
            val listed = if (link != null) t in link.waiters else t in zone.waiters
            if (!listed) {
                violate(
                    "transporter (${t.name}) is blocked waiting for ($named), which does not list " +
                            "it among those waiting -- so nothing will wake it"
                )
            }
            if (zone.holder === t) {
                violate(
                    "transporter (${t.name}) is blocked waiting for zone (${zone.name}), which it " +
                            "holds itself"
                )
            }
            // Waiting for a zone with neither a holder nor occupants is the stall this check
            // exists to find: nothing is in the way, so nothing will ever come through
            // `becameAvailable` to offer it the zone. Being held or populated are both fine --
            // whatever is in the way will offer the zone when it goes.
            if (link == null && zone.holder == null && zone.numPresent == 0 &&
                zone.closingFor == null
            ) {
                violate(
                    "transporter (${t.name}) is blocked waiting for zone (${zone.name}), which is " +
                            "available -- nothing holds it, nothing is in it and nothing has been " +
                            "promised it, so nothing will ever wake the transporter"
                )
            }
        }
    }

    /**
     * The exhaustive form of [checkWaitingIsConsistent]: that no waiting list holds a transporter
     * which is not blocked and waiting for exactly that thing.
     *
     * Searching every zone and every link is what makes this too expensive to do continuously, and
     * once per replication is enough: a transporter wrongly left in a list stays there, so the
     * sweep at the end of the replication finds it just as surely as one at every instant would.
     */
    private fun checkNoStrayWaiters() {
        for (t in mySystem.transporters) {
            val zonesWaitedOn = mySystem.network.zones.filter { t in it.waiters }
            val linksWaitedOn = mySystem.network.links.filter { t in it.waiters }
            val places = zonesWaitedOn.size + linksWaitedOn.size
            val expected = if (t.transporterState == TransporterState.BLOCKED) 1 else 0
            if (places != expected) {
                violate(
                    "transporter (${t.name}) is ${t.transporterState} but is waiting in $places " +
                            "list(s) rather than $expected: zones " +
                            "${zonesWaitedOn.joinToString { it.name }}, links " +
                            linksWaitedOn.joinToString { it.name }
                )
            }
        }
    }

    /**
     * A zone is held by at most one transporter, and a zone that believes it is covered is covered
     * by a transporter that agrees.
     */
    private fun checkZonesAgreeWithTransporters() {
        for (zone in mySystem.network.zones) {
            val holder = zone.holder
            // The whole of the exclusion rule, asserted: a zone is held or it is populated, never
            // both. Every other guarantee about vehicles and crowds staying out of each other's
            // way is a consequence of this one, which is why it is worth stating directly rather
            // than inferring from the claim and admission paths being right.
            if (zone.numPresent < 0) {
                violate("zone (${zone.name}) reports ${zone.numPresent} occupants")
            }
            if (holder != null && zone.numPresent > 0) {
                violate(
                    "zone (${zone.name}) is held by (${holder.name}) and also has " +
                            "${zone.numPresent} occupant(s) present"
                )
            }
            // A zone closing for one holder while another still holds it is the *normal* state of
            // a drain, and the whole point of draining rather than evicting -- so there is nothing
            // to assert about that. What must not happen is a zone closing for the very holder that
            // has already taken it: the claim that takes a promised zone clears the promise in the
            // same breath, so a zone still promised to its own holder means that did not happen,
            // and the promise would go on excluding everyone for the rest of the replication.
            val promisedTo = zone.closingFor
            if (promisedTo != null && promisedTo === holder) {
                violate(
                    "zone (${zone.name}) is held by (${promisedTo.name}) and is still closing for " +
                            "it, so the reservation was never cleared and nothing else can ever " +
                            "have the zone"
                )
            }
            // A set is taken together or not at all, asserted rather than trusted, and asserted
            // from the other end: a zone still closing means the grant has not happened, so *no*
            // zone of that closure may be held by the holder it is closing for. Holding part of a
            // region while the rest drains is what the all-or-nothing grant exists to prevent,
            // because a vehicle inside the region could then wait for a zone the occupier holds
            // while the occupier waits for the zone the vehicle is standing in -- a deadlock with
            // no edge in the wait-for graph, and so one the detector cannot see.
            val closing = zone.closure
            if (closing != null) {
                checkHolderDiscipline(closing.holder, "is closing ${zone.name} for")
                val partlyHeld = closing.zones.filter { it.holder === closing.holder }
                if (partlyHeld.isNotEmpty()) {
                    violate(
                        "zone (${zone.name}) is still closing for (${closing.holder.name}), which " +
                                "already holds ${partlyHeld.joinToString { it.name }} of the same " +
                                "closure -- so a region is part held and part draining"
                    )
                }
            }
            if (zone.state == ZoneState.FREE) {
                if (holder != null) {
                    violate("zone (${zone.name}) is free but is held by (${holder.name})")
                }
                continue
            }
            if (holder == null) {
                violate("zone (${zone.name}) is ${zone.state} but names no holder")
                continue
            }
            // Only a vehicle is asked to agree. The two-sided bookkeeping below is a statement
            // about a transporter's own run of zones, and a holder that merely occupies space --
            // a closed aisle, a crossing -- keeps no such run to disagree with. Its claim is
            // one-sided by construction, so there is nothing here to check.
            if (holder !is GuidedTransporter) {
                checkHolderDiscipline(holder, "holds ${zone.name} and")
                continue
            }
            if (zone.state == ZoneState.CLAIMED && holder.claimedZone !== zone) {
                violate(
                    "zone (${zone.name}) is claimed by (${holder.name}), but that transporter is " +
                            "claiming (${holder.claimedZone?.name ?: "nothing"})"
                )
            }
            if (zone.state == ZoneState.COVERED && zone !in holder.coveredZones) {
                violate(
                    "zone (${zone.name}) is covered by (${holder.name}), but that transporter " +
                            "does not count it among the zones it covers: " +
                            holder.coveredZones.joinToString { it.name }
                )
            }
        }
    }

    /**
     * A holder that is not a vehicle never holds guide-path space and waits for more of it at once.
     *
     * The all-or-nothing grant is what makes this true -- a holder holds nothing until every zone
     * of its request has drained -- and the space enforces one request per holder on top of it.
     * Asserting it is worth the two lines because it was a property of the *type* until
     * `ZoneOccupier` was deleted, and is now a property of the mechanism instead.
     *
     * **What this does not establish**, and what §18.2 of the design record wrongly claimed it
     * did, is that such a holder cannot lie on a circular wait. Holding nothing removes one edge:
     * the holder holding what a vehicle wants. It leaves two others. A *pending* closure waits on
     * every vehicle occupying the zones it has reserved, and the reservation is what a vehicle
     * refused entry is waiting on. Two closures over adjacent regions can each trap a vehicle in
     * the other's way -- see `MutualPromiseDeadlockTest` -- so acyclicity is not an invariant here
     * and is not asserted. The detector is what reports that case, and reporting it is the remedy
     * the subsystem offers for every other circular wait too.
     *
     * [ZoneHolderIfc.awaitedZone] is checked as well, for a narrower reason: the detector treats a
     * non-null answer as a vehicle-style edge, so a holder that reported one would be counted twice
     * over -- once through its own edge and once through the reservation it made.
     */
    private fun checkHolderDiscipline(holder: ZoneHolderIfc, doing: String) {
        if (mySystem.isHoldingZones(holder) && mySystem.isWaitingForZones(holder)) {
            violate(
                "holder (${holder.name}) $doing both holds space and has a request outstanding. A " +
                        "set of zones is taken together or not at all, so a holder holds nothing " +
                        "until every zone of its request has drained"
            )
        }
        val awaited = holder.awaitedZone ?: return
        violate(
            "holder (${holder.name}) $doing reports awaiting zone (${awaited.name}). A holder that " +
                    "is not a vehicle must answer null: what it waits for is its own reservation " +
                    "draining, which the detector already follows through the reservation, and an " +
                    "awaited zone here would be counted as a second, vehicle-style edge as well"
        )
    }

    /**
     * Each transporter covers a run of adjacent zones, in order from its rear to its front, no
     * longer than the transporter itself.
     */
    private fun checkTransportersCoverContiguousRuns() {
        for (t in mySystem.transporters) {
            val zones = t.coveredZones
            // Held, not covered: a transporter one zone long that has given up the zone behind at
            // the moment travel began is briefly between zones, covering none and holding only the
            // one it is entering. It still denies exactly one zone to everyone else, which is what
            // has to be true.
            if (t.heldZones.isEmpty()) {
                violate("transporter (${t.name}) holds no zones at all")
                continue
            }
            if (zones.size > t.lengthInZones) {
                violate(
                    "transporter (${t.name}) covers ${zones.size} zones but is only " +
                            "${t.lengthInZones} long: ${zones.joinToString { it.name }}"
                )
            }
            for (zone in zones) {
                if (zone.holder !== t) {
                    violate(
                        "transporter (${t.name}) counts zone (${zone.name}) among the zones it " +
                                "covers, but that zone is held by (${zone.holder?.name ?: "no one"})"
                    )
                }
                if (zone.state != ZoneState.COVERED) {
                    violate(
                        "transporter (${t.name}) covers zone (${zone.name}), which is " +
                                "${zone.state} rather than covered"
                    )
                }
            }
            val held = t.heldZones
            for (i in 0 until held.size - 1) {
                if (!areAdjacent(held[i], held[i + 1])) {
                    violate(
                        "transporter (${t.name}) holds a broken run: (${held[i].name}) is not " +
                                "adjacent to (${held[i + 1].name})"
                    )
                }
            }
            val claimed = t.claimedZone
            if (claimed != null) {
                if (claimed.holder !== t) {
                    violate(
                        "transporter (${t.name}) believes it has claimed (${claimed.name}), which " +
                                "is held by (${claimed.holder?.name ?: "no one"})"
                    )
                }
                if (claimed.state != ZoneState.CLAIMED) {
                    violate(
                        "transporter (${t.name}) has claimed (${claimed.name}), which reports " +
                                "${claimed.state} rather than being claimed"
                    )
                }
            }
        }
    }

    /** The zones believed covered and the zones transporters believe they cover are the same set. */
    private fun checkCoverageIsConserved() {
        val byZones = mySystem.network.zones.count { it.state == ZoneState.COVERED }
        val byTransporters = mySystem.transporters.sumOf { it.coveredZones.size }
        if (byZones != byTransporters) {
            violate(
                "$byZones zones report being covered, but transporters between them claim to " +
                        "cover $byTransporters"
            )
        }
    }

    /**
     * Whether one zone leads directly to another, in either direction. A transporter's own body
     * spans zones without regard to which way it is facing, so adjacency here is symmetric.
     */
    private fun areAdjacent(first: Zone, second: Zone): Boolean {
        if (first is LinkZone && second is LinkZone && first.link === second.link) {
            return kotlin.math.abs(first.positionOnLink - second.positionOnLink) == 1
        }
        if (first is LinkZone && second is IntersectionZone) {
            return (first.isLastOnLink && first.link.endIntersection === second.intersection) ||
                    (first.isFirstOnLink && first.link.beginIntersection === second.intersection)
        }
        if (first is IntersectionZone && second is LinkZone) {
            return areAdjacent(second, first)
        }
        return false
    }

    private fun violate(what: String): Nothing = throw ZoneInvariantViolation(
        "Guide path invariant violated in system (${mySystem.name}) in the state left at the end " +
                "of time $myAuditedTime" +
                (if (myAuditedTime != mySystem.time) " (found at ${mySystem.time})" else "") +
                ": $what."
    )
}
