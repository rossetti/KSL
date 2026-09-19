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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.IntersectionZone
import ksl.modeling.guidedpath.Link
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.LinkZone
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.VelocitySampling
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.routing.Route
import ksl.modeling.guidedpath.rules.ZoneReleaseTiming

/**
 * Moves transporters, one zone at a time.
 *
 * **Every zone state change in the subsystem happens here.** Nothing else claims, occupies, or
 * releases a zone. That is the property the whole design rests on: exclusivity can be established
 * by reading one file rather than by auditing every caller, and it is why the zone mutators are not
 * visible outside the package.
 *
 * Each transporter propels itself. It claims the zone ahead, schedules its own arrival, and on
 * arriving either claims the next or stops. There is no fleet-wide movement event and no
 * propagation of a stoppage backwards down a queue of vehicles: a transporter that cannot proceed
 * simply schedules nothing, which costs the executive nothing at all while it waits. This is the
 * fundamental difference from a conveyor, where one engine advances everything at once and a
 * blockage travels backwards along the line.
 */
internal class MovementEngine(
    private val mySystem: GuidedPathSpace
) {

    private val myNetwork: GuidedPathNetwork
        get() = mySystem.network

    /**
     * Plans a route and sets a transporter travelling.
     *
     * @return true when a movement began, false when the transporter was already at the destination
     */
    fun startMove(
        transporter: GuidedTransporter,
        destination: GuidedPathNetwork.Intersection,
        purpose: MovePurpose
    ): Boolean {
        // The outstanding claim is settled before anything is asked about where the transporter
        // stands, because a transporter part way into a zone may legitimately be standing nowhere.
        // Under release-at-start a transporter as long as one zone gives that zone up the instant
        // it begins to leave it, so for the whole of the traversal it covers no zone at all and
        // holds only its claim. Reading its front zone first would call that unplaced and refuse a
        // redirection the next branch is there to accept.
        if (transporter.claimedZone != null) {
            // Already travelling into a zone. A vehicle between two places cannot stop and turn
            // round, so the redirection waits for the boundary. The reservation it holds is
            // therefore always a reservation it will use, which is what stops a superseded
            // movement leaving a zone held by a transporter that never arrives.
            transporter.pendingDestination = destination
            transporter.pendingPurpose = purpose
            return true
        }
        val front = transporter.frontZone
            ?: throw IllegalStateException(
                "Transporter (${transporter.name}) has not been placed and cannot be moved."
            )
        // A blocked transporter holds no claim, so it may be sent somewhere else -- and it is
        // worth being able to, since a cart stopped on its way back to a parking spur is doing
        // work nobody needs while an entity waits for one. But it is on a waiter list, and that
        // has to be given up first: leaving it there would put it on the list twice once it
        // blocked again, and would have it woken later for a journey it is no longer making.
        cancelWait(transporter)
        if (front === destination.zone) return false
        transporter.currentVelocity = transporter.sampleVelocity()
        transporter.currentRoute =
            myNetwork.routeFrom(front, destination, transporter.travellingForward)
        creditOwnLengthIfReversing(transporter, front)
        transporter.transporterState = transporter.movingStateFor(purpose)
        mySystem.refreshFleetCounts()
        advance(transporter)
        return true
    }

    /**
     * Takes one step: claim the zone ahead and schedule the arrival, or stop and wait.
     *
     * On every path out of this function the transporter is either scheduled to arrive somewhere or
     * is waiting for a stated thing to change. It is never left with nothing scheduled and nothing
     * to wait for, which would be a transporter lost in the middle of the network -- and because a
     * waiting transporter schedules nothing at all, a lost wake-up would be a permanent stall
     * rather than a slow recovery. That is why the two outcomes are kept exhaustive here.
     */
    fun advance(transporter: GuidedTransporter) {
        val route = transporter.currentRoute
            ?: throw IllegalStateException(
                "Transporter (${transporter.name}) was advanced without a route."
            )
        val next = route.nextZone
        if (next == null) {
            arrive(transporter)
            return
        }
        // A link can hold a transporter up even when the zone it wants is free: the link may be
        // running the other way, or be a spur that someone else is down. Those are settled first,
        // because what frees the transporter is a change to the link rather than to the zone.
        val blockingLink = linkRefusing(transporter, next, route)
        if (blockingLink != null) {
            blockOnLink(transporter, blockingLink, next)
            return
        }
        if (!next.claim(transporter)) {
            blockOnZone(transporter, next)
            return
        }
        transporter.claimedZone = next
        acquireLinkHolds(transporter, next, route)
        val velocity = transporter.velocityForNextZone()
        if (transporter.velocitySampling == VelocitySampling.PER_ZONE) {
            transporter.currentVelocity = velocity
        }
        val plan = traversalPlan(transporter, next, velocity)
        val traversalTime = plan.duration
        transporter.beginTraversal(plan.distance, plan.duration)
        when (val timing = transporter.zoneControlRule.releaseTiming(transporter, next)) {
            is ZoneReleaseTiming.AtStart -> releaseRearAtStart(transporter)

            is ZoneReleaseTiming.AtEnd -> Unit

            is ZoneReleaseTiming.AfterDistance -> {
                if (next.length <= 0.0) {
                    // A junction treated as a point has no distance to travel into, so the release
                    // can only be immediate. Refusing instead would make this rule unusable on any
                    // network at all, since every route crosses junctions.
                    releaseRearAtStart(transporter)
                } else {
                    // Zone sizes vary from link to link, so a rule with one fixed distance will
                    // meet zones shorter than it. Releasing at the far end of such a zone is the
                    // only reading that stays monotone in the distance asked for; refusing would
                    // make the rule unusable wherever a network mixes zone sizes, which is common.
                    val effective = minOf(timing.distance, next.length)
                    val delay = effective / (velocity * next.velocityFactor)
                    mySystem.scheduleRearRelease(transporter, delay)
                }
            }
        }
        mySystem.scheduleTraversal(transporter, next, traversalTime)
    }

    /**
     * Grants a reversing transporter credit for its own length against the way out.
     *
     * A transporter given a [GuidedTransporter.physicalLength] is a body rather than a point. One
     * standing at the end of a spur has already covered its own length of that spur, so when it
     * turns round, the end that now leads is that much nearer the way out and the journey back is
     * shorter by exactly its length. A transporter sized in zones has no length to credit and this
     * does nothing, which is why every model written before this behaves exactly as it did.
     *
     * Only a reversal earns it. Travelling on in the same direction, the leading end is the same end
     * it always was and there is nothing to give back.
     */
    private fun creditOwnLengthIfReversing(transporter: GuidedTransporter, front: Zone) {
        val length = transporter.physicalLength ?: return
        val next = transporter.currentRoute?.nextZone ?: return
        if (isReversing(transporter, front, next)) {
            transporter.lengthCreditRemaining = length
        }
    }

    /**
     * Whether the first step of the new route sends the transporter back the way it came.
     *
     * Two shapes, and the second is the one a spur usually takes. Either the next zone lies behind
     * the transporter on the link it is already on, or the next zone is the intersection it entered
     * that link by -- which is how leaving a single-zone spur looks, there being no zone behind to
     * step back into.
     */
    private fun isReversing(transporter: GuidedTransporter, front: Zone, next: Zone): Boolean {
        // Standing at a dead end. There is one link in and the same link out, so leaving is always
        // a reversal -- and this is the case the credit exists for, since a spur is where a body
        // with length is obliged to back out of somewhere it has driven into. A transporter that
        // has arrived at the far end of a spur stands on the terminal *intersection's* zone rather
        // than on the link's last zone, because arriving at a link's far end means arriving at the
        // junction beyond it, so this is the shape the common case actually takes.
        //
        // Deliberately **not** extended to a reversal at an ordinary junction, though the physics
        // reads the same way: a body there also extends back along the link it came in on. Crediting
        // those was tried against Exercise 7.13(a), whose single vehicle turns round constantly on
        // two-way aisles, and it overshot -- mean transfer time went from 0.03 minutes above the
        // reference implementation's to 0.10 below it. Whatever that implementation does at a
        // junction, it is not this, and shipping a wider rule that a measurement contradicts would
        // be worse than shipping the narrow one that two independent measurements confirm.
        if (front is IntersectionZone && front.intersection.incidentLinks.size == 1) return true
        if (front !is LinkZone) return false
        // Stopped part way along a link and sent back the way it came.
        if (next is LinkZone && next.link === front.link) {
            val goingUp = next.positionOnLink > front.positionOnLink
            return goingUp != transporter.travellingForward
        }
        if (next is IntersectionZone) {
            val enteredBy =
                if (transporter.travellingForward) front.link.beginIntersection
                else front.link.endIntersection
            return next.intersection === enteredBy
        }
        return false
    }

    /** How far the next zone is and how long it takes. */
    private class TraversalPlan(val distance: Double, val duration: Double)

    /**
     * The ground the next zone costs, less whatever of the transporter's own length is still owed
     * to it after a reversal.
     *
     * The credit is spent zone by zone rather than all at once, because a transporter may be longer
     * than the zone it is turning round in. A zone entirely covered by the credit costs no time and
     * no distance at all, which is right: the leading end was already past it.
     *
     * Distance and duration come out together because they must agree about the credit. Computing
     * the duration here and the distance anywhere else would let a transporter's odometer and its
     * arrival time describe different journeys.
     */
    private fun traversalPlan(
        transporter: GuidedTransporter,
        next: Zone,
        velocity: Double
    ): TraversalPlan {
        if (transporter.lengthCreditRemaining <= 0.0) {
            return TraversalPlan(next.length, next.traversalTime(velocity))
        }
        val credit = minOf(transporter.lengthCreditRemaining, next.length)
        transporter.lengthCreditRemaining -= credit
        val remaining = next.length - credit
        if (remaining <= 0.0) return TraversalPlan(0.0, 0.0)
        return TraversalPlan(remaining, remaining / (velocity * next.velocityFactor))
    }

    /**
     * The link standing in a transporter's way, or null when none is.
     *
     * Only a transporter *entering* a link can be refused by it. One already on a link has its
     * direction and, where it matters, its reservation, and must be allowed to finish: refusing it
     * part way along would strand it somewhere it cannot legally be.
     */
    private fun linkRefusing(
        transporter: GuidedTransporter,
        next: Zone,
        route: Route
    ): Link? {
        if (next is IntersectionZone) {
            // A transporter heading for the far end of a spur must not take the junction at the
            // spur's mouth while another is still down there. The one down there can only come out
            // through that junction, so letting a second in would leave the two facing each other
            // with neither able to move -- which is the deadlock a spur is supposed to prevent.
            // Traffic going anywhere else through the same junction is untouched.
            for (spur in next.intersection.incidentLinks) {
                if (spur.type != LinkType.SPUR) continue
                if (spur.beginIntersection !== next.intersection) continue
                if (route.destination !== spur.endIntersection) continue
                val reservation = spur.spurReservation
                if (reservation != null && reservation !== transporter) return spur
            }
            return null
        }
        if (next !is LinkZone) return null
        val link = next.link
        if (transporter.heldZones.any { it is LinkZone && it.link === link }) return null
        val forward = next.positionOnLink == 1
        if (!link.admitsDirection(forward)) return link
        if (needsSpurReservation(link, route) &&
            link.spurReservation != null &&
            link.spurReservation !== transporter
        ) {
            return link
        }
        return null
    }

    /**
     * Whether entering this link means taking it over entirely: true when it is a spur and the
     * route ends at its far end.
     *
     * A transporter merely passing through the mouth of a spur takes nothing over, which is the
     * distinction that lets a spur park a transporter out of the way without stopping other
     * traffic.
     */
    private fun needsSpurReservation(link: Link, route: Route): Boolean =
        link.type == LinkType.SPUR && route.destination === link.endIntersection

    /** Takes whatever the link requires of a transporter that is entering it. */
    private fun acquireLinkHolds(transporter: GuidedTransporter, next: Zone, route: Route) {
        if (next !is LinkZone) return
        val link = next.link
        // Only on entry: a transporter already on the link took these when it arrived.
        val alreadyOn = transporter.heldZones.any { it is LinkZone && it.link === link && it !== next }
        if (alreadyOn) return
        link.acquireDirection(next.positionOnLink == 1)
        if (needsSpurReservation(link, route)) {
            link.spurReservation = transporter
            transporter.reservedSpur = link
        }
    }

    /**
     * Whether a transporter has genuinely left a spur.
     *
     * The junction at the dead end counts as part of the spur. A transporter standing there covers
     * no zone of the spur at all, but it has not left: the only way out is back down. Treating it
     * as gone is what would let a second transporter in behind it, leaving the two facing each
     * other with no way past -- the very situation a spur exists to prevent.
     */
    private fun isClearOfSpur(transporter: GuidedTransporter, spur: Link): Boolean {
        val terminal = spur.endIntersection.zone
        return transporter.heldZones.none {
            it === terminal || (it is LinkZone && it.link === spur)
        }
    }

    /** Gives up a spur once its transporter is clear of both the spur and the junction at its end. */
    private fun releaseSpurIfClear(transporter: GuidedTransporter) {
        val spur = transporter.reservedSpur ?: return
        if (!isClearOfSpur(transporter, spur)) return
        transporter.reservedSpur = null
        if (spur.spurReservation === transporter) {
            spur.spurReservation = null
        }
        wakeOneWaiterOf(spur)
    }

    /**
     * Gives up whatever a link required, once the transporter has left it entirely.
     *
     * Called after a zone is released, because leaving a link is exactly the moment its last zone
     * stops being held.
     */
    private fun releaseLinkHoldsIfClearOf(transporter: GuidedTransporter, released: Zone) {
        if (released !is LinkZone) return
        val link = released.link
        if (transporter.heldZones.any { it is LinkZone && it.link === link }) return
        val nowClear = link.releaseDirection()
        // Anyone held up by the link may now be able to go. Waking exactly one keeps the choice
        // deliberate; if it turns out still to be refused it simply waits again. The spur is left
        // alone here: leaving its zones is not the same as leaving the spur.
        if (nowClear) {
            wakeOneWaiterOf(link)
        }
    }

    private fun wakeOneWaiterOf(link: Link) {
        if (link.numWaiting == 0) return
        val chosen = mySystem.zoneContentionRule.selectWaiter(link.zones.first(), link.waiters)
        check(chosen in link.waiters) {
            "Zone contention rule (${mySystem.zoneContentionRule}) chose transporter " +
                    "(${chosen.name}), which is not waiting for link (${link.name}). A rule must " +
                    "choose from the transporters it is given."
        }
        link.removeWaiter(chosen)
        mySystem.scheduleClaimRetry(chosen)
    }

    /** Records that a transporter is waiting for a zone someone else holds, and stops it. */
    private fun blockOnZone(transporter: GuidedTransporter, zone: Zone) {
        zone.addWaiter(transporter)
        transporter.awaitedZone = zone
        transporter.awaitedLink = null
        beginBlocking(transporter)
    }

    /** Records that a transporter is waiting for a link to change, and stops it. */
    private fun blockOnLink(transporter: GuidedTransporter, link: Link, zone: Zone) {
        link.addWaiter(transporter)
        transporter.awaitedZone = zone
        transporter.awaitedLink = link
        beginBlocking(transporter)
    }

    /**
     * Takes a waiting transporter off whatever it was waiting for and stands it down where it is.
     *
     * Called only when a waiting transporter is redirected. It settles the transporter exactly as
     * an arrival does, short of announcing one -- nobody is waiting to be told, because the journey
     * it was on has been abandoned rather than completed.
     *
     * A transporter held up by a link is on that link's waiter list and not on the awaited zone's,
     * so both are cleared; removing a transporter that was not waiting is defined to do nothing,
     * which is what makes handling the two cases together safe rather than merely convenient.
     */
    private fun cancelWait(transporter: GuidedTransporter) {
        if (transporter.transporterState != TransporterState.BLOCKED) return
        transporter.awaitedZone?.removeWaiter(transporter)
        transporter.awaitedLink?.removeWaiter(transporter)
        transporter.awaitedZone = null
        transporter.awaitedLink = null
        releaseRearIfSurplus(transporter)
        transporter.currentRoute = null
        transporter.transporterState = TransporterState.IDLE
        transporter.currentLocation = mySystem.locationOf(transporter)
        mySystem.refreshFleetCounts()
    }

    private fun beginBlocking(transporter: GuidedTransporter) {
        if (transporter.transporterState != TransporterState.BLOCKED) {
            transporter.stateBeforeBlocking = transporter.transporterState
            transporter.transporterState = TransporterState.BLOCKED
            transporter.countBlocking()
        }
        mySystem.refreshFleetCounts()
        // Nothing is scheduled. A waiting transporter costs the executive nothing while it waits,
        // and is started again only by whoever releases what it is waiting for.
        //
        // This is the one moment a circular wait can come into existence, so it is the one place
        // the wait-for graph is examined. The call is last, after the transporter's held zones,
        // awaited zone, and state are all in place, because the detector reads exactly those.
        mySystem.transporterBlocked(transporter)
    }

    /**
     * Starts a transporter again after whatever it was waiting for has come free.
     *
     * The retry is a scheduled event rather than a direct call from the releasing transporter, so
     * that its ordering against everything else happening at that instant is explicit rather than
     * an artefact of the call stack, and so that one release cannot set off an unbounded chain of
     * wake-ups inside a single event.
     */
    fun retryClaim(transporter: GuidedTransporter) {
        if (transporter.transporterState != TransporterState.BLOCKED) return
        transporter.awaitedZone = null
        transporter.awaitedLink = null
        transporter.transporterState = transporter.stateBeforeBlocking
        mySystem.refreshFleetCounts()
        advance(transporter)
    }

    /**
     * Takes the direction of travel on any two-way link a transporter was placed on.
     *
     * A transporter standing on a two-way link is as much an obstacle to oncoming traffic as one
     * moving along it, so the link is running its way from the moment the replication begins.
     * Without this, the first transporter to be sent somewhere would find the link unclaimed and
     * another could enter against it.
     */
    fun acquirePlacementHolds(transporter: GuidedTransporter) {
        val links = transporter.coveredZones.filterIsInstance<LinkZone>().map { it.link }.distinct()
        for (link in links) {
            link.acquireDirection(transporter.travellingForward)
        }
    }

    /** Completes a traversal: the transporter now covers the zone it was travelling into. */
    fun endZoneTraversal(transporter: GuidedTransporter, zone: Zone) {
        // First, so that nothing reached from here reads an odometer that still has this traversal
        // in progress and counts part of it twice.
        transporter.endTraversal()
        zone.cover(transporter)
        transporter.claimedZone = null
        transporter.addFrontZone(zone)
        mySystem.countZoneTraversal()
        mySystem.emitTransporterMoved(transporter, zone)
        transporter.travellingForward = directionAfterEntering(transporter, zone)
        val route = transporter.currentRoute
            ?: throw IllegalStateException(
                "Transporter (${transporter.name}) completed a traversal without a route."
            )
        route.advance()
        if (transporter.zoneControlRule.releaseTiming(transporter, zone) is ZoneReleaseTiming.AtEnd) {
            releaseRearIfSurplus(transporter)
        }
        val redirect = transporter.pendingDestination
        if (redirect != null) {
            transporter.pendingDestination = null
            if (zone === redirect.zone) {
                // The redirection asked for somewhere the transporter has just reached.
                transporter.currentRoute = null
                arrive(transporter)
                return
            }
            transporter.currentVelocity = transporter.sampleVelocity()
            transporter.currentRoute =
                myNetwork.routeFrom(zone, redirect, transporter.travellingForward)
            // Derived when the redirection is actually applied rather than when it was issued, so
            // the state describes what the transporter is carrying at the moment it sets off.
            transporter.transporterState = transporter.movingStateFor(transporter.pendingPurpose)
        }
        mySystem.refreshFleetCounts()
        // The one place a transporter can be stopped without leaving the guide path in a state this
        // engine cannot describe: it has finished entering a zone, it covers that zone, and it has
        // route left to run. Asked only when there is somewhere further to go -- a transporter that
        // has reached its destination arrives, whatever a gate would have said about carrying on.
        if (transporter.currentRoute?.nextZone != null && !transporter.mayContinuePast(zone)) {
            transporter.halt()
            // The route is kept, so that a consumer which merely wants the transporter to pause and
            // then carry on the same way can say so with `resumeHaltedTransporter`. One that intends
            // to move it somewhere else instead issues a fresh journey, which computes its own route
            // from wherever the transporter has ended up.
            mySystem.transporterInterrupted(transporter)
            return
        }
        advance(transporter)
    }

    /**
     * Starts a halted transporter again from where it stopped.
     *
     * It kept its zones and its route, so there is nothing to rebuild: restoring the state it was
     * halted out of and advancing is the whole of it. The gate is asked again at the next boundary,
     * so a transporter released while the condition that halted it still holds gets exactly one
     * zone further and stops again.
     */
    fun resumeHalted(transporter: GuidedTransporter) {
        if (!transporter.isHalted) return
        transporter.releaseHalt()
        advance(transporter)
    }

    /**
     * Gives up zones behind that the transporter has outgrown: the test that applies once a zone
     * has been entered, and the one that settles a transporter to its own length when it stops.
     */
    fun releaseRearIfSurplus(transporter: GuidedTransporter) {
        while (transporter.hasSurplusZones) {
            val rear = transporter.removeRearZone() ?: return
            releaseZone(transporter, rear)
        }
    }

    /**
     * Gives up the zone behind at the moment travel begins, freeing it for a follower straight
     * away, but only once the transporter is fully on the guide path. One still driving on covers
     * fewer zones than it is long and has nothing to spare, which is the case that a release
     * applied unconditionally would get wrong.
     */
    fun releaseRearAtStart(transporter: GuidedTransporter) {
        if (!transporter.isFullyOnPath) return
        val rear = transporter.removeRearZone() ?: return
        releaseZone(transporter, rear)
    }

    /**
     * Gives up one zone: hands it to whoever was waiting for it, and gives up anything the link
     * required once the transporter is clear of it.
     */
    private fun releaseZone(transporter: GuidedTransporter, zone: Zone) {
        val offeredTo = zone.release(transporter, mySystem.zoneContentionRule)
        releaseLinkHoldsIfClearOf(transporter, zone)
        releaseSpurIfClear(transporter)
        // Whoever it went to, and it need not be a vehicle: a zone that was closing for an occupier
        // has just finished draining. The space tells the two apart.
        mySystem.handOver(offeredTo)
    }

    /**
     * The delayed form: give up the zone behind after travelling a stated distance into the zone
     * ahead. Guarded on the claim still being outstanding, so that a release scheduled for the very
     * end of a traversal cannot arrive after the transporter has settled and take a zone its body
     * is standing in.
     */
    fun releaseRearAfterDistance(transporter: GuidedTransporter) {
        if (transporter.claimedZone == null) return
        releaseRearAtStart(transporter)
    }

    private fun arrive(transporter: GuidedTransporter) {
        // Settle to exactly the transporter's own length. Nothing else releases here: a control
        // rule that gives up the zone behind at the start of a traversal has no traversal left to
        // start, so without this the transporter would stand on more of the guide path than it
        // covers and deny that space to everyone else for the rest of the run.
        releaseRearIfSurplus(transporter)
        transporter.currentRoute = null
        transporter.transporterState = TransporterState.IDLE
        transporter.currentLocation = mySystem.locationOf(transporter)
        mySystem.refreshFleetCounts()
        transporter.notifyArrival()
        ProcessModel.logger.trace {
            "GUIDED PATH (${mySystem.name}): transporter (${transporter.name}) arrived at " +
                    "(${transporter.frontZone?.name})"
        }
    }

    /**
     * Which way the transporter now faces, taken from the zone it came from rather than from where
     * the entered zone sits on its link. A link of a single zone has its first and last zone in the
     * same place, so position cannot tell the two directions apart.
     */
    private fun directionAfterEntering(transporter: GuidedTransporter, entered: Zone): Boolean {
        if (entered !is LinkZone) return transporter.travellingForward
        val previous = transporter.coveredZones.let {
            if (it.size >= 2) it[it.size - 2] else null
        } ?: return transporter.travellingForward
        if (previous is LinkZone && previous.link === entered.link) {
            return entered.positionOnLink > previous.positionOnLink
        }
        if (previous is IntersectionZone) {
            return entered.link.beginIntersection === previous.intersection
        }
        return transporter.travellingForward
    }
}
