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

import kotlinx.serialization.Serializable

/**
 * How traffic may traverse a link.
 *
 * The choice matters far beyond direction of travel, because it decides which deadlocks are
 * possible. A unidirectional network cannot produce a head-on circular wait at all, which is why it
 * is the default and why the standard advice is to prefer it.
 */
@Serializable
enum class LinkType {

    /** Traversable only from the beginning intersection toward the ending intersection. */
    UNIDIRECTIONAL,

    /**
     * Traversable in both directions, but only by one direction of travel at a time. A transporter
     * entering must agree with the direction of any transporters already on the link, which is
     * enforced by the link's direction lock. Bidirectional links are the usual source of head-on
     * deadlock and are worth avoiding where a pair of unidirectional links would serve.
     */
    BIDIRECTIONAL,

    /**
     * A dead end: bidirectional, and ending at an intersection with no other incident link. A
     * transporter sent to the far end must keep a claim sufficient to get back out, which is what
     * makes a spur the standard device for keeping a stopped transporter out of the way of traffic.
     */
    SPUR
}

/**
 * The travel direction currently permitted on a bidirectional link, and how many transporters are
 * relying on it.
 *
 * A lock is engaged by the first transporter to enter an empty bidirectional link and released when
 * the last one leaves. While it is engaged, a transporter wanting the opposite direction waits.
 * Declared here in the static layer so that a link owns its own lock, but not exercised until
 * transporters exist and can move.
 *
 * @param forward true when travel runs from the link's beginning intersection toward its ending
 *   intersection, false for the reverse
 * @param count how many transporters currently hold the link in that direction, always positive
 *   while the lock is engaged
 */
data class DirectionLock(val forward: Boolean, val count: Int) {
    init {
        require(count > 0) { "An engaged direction lock must be held by at least one transporter." }
    }
}

/**
 * A run of guide path between two intersections, divided into an integral number of equal zones.
 *
 * A link is the unit a modeler specifies and the unit over which zone geometry is uniform. Its
 * length must be an integral multiple of its zone length, so that the space it represents divides
 * evenly into zones that a transporter can claim one at a time. That constraint is checked when
 * the network is built rather than when a transporter first traverses the link, because a geometry
 * error discovered part way through a long run is far harder to diagnose than a rejected
 * specification.
 *
 * The velocity change factor multiplies a transporter's velocity while it is on this link, which is
 * how a stretch of path that must be taken slowly is represented without distorting its length.
 *
 * Links are created by the network builder and are immutable apart from the direction lock, which
 * is per-replication state.
 *
 * @param name unique within the network
 * @param beginIntersection where a forward traversal starts
 * @param endIntersection where a forward traversal ends, distinct from the beginning
 * @param length the total length in the modeler's units
 * @param zoneLength the length of each of this link's zones
 * @param numZones how many zones divide the link, at least one
 * @param type whether traffic may run one way, both ways, or into a dead end
 * @param velocityFactor multiplies transporter velocity on this link, strictly positive
 * @param beginDirection the compass-style direction of travel in degrees as the link leaves its
 *   beginning intersection. Layout and animation metadata only: it does not affect travel time,
 *   because turn and acceleration dynamics are outside this subsystem.
 */
class Link internal constructor(
    val name: String,
    val beginIntersection: GuidedPathNetwork.Intersection,
    val endIntersection: GuidedPathNetwork.Intersection,
    val length: Double,
    val zoneLength: Double,
    val numZones: Int,
    val type: LinkType,
    val velocityFactor: Double,
    val beginDirection: Double,
    nextZoneId: () -> Int
) {

    /**
     * This link's zones, ordered from the beginning intersection toward the ending intersection.
     * Index order is travel order in the forward direction.
     */
    val zones: List<LinkZone> = (1..numZones).map { LinkZone(nextZoneId(), this, it) }

    /**
     * The direction currently permitted on a bidirectional link, or null when the link is empty or
     * unidirectional. Reset at the start of every replication.
     */
    var directionLock: DirectionLock? = null
        internal set

    /** True when traffic may run from the ending intersection back toward the beginning. */
    val isTraversableInReverse: Boolean
        get() = type != LinkType.UNIDIRECTIONAL

    /**
     * The transporter that has reserved this spur so that it can get back out, or null.
     *
     * A dead end has to be entered and left by the same way. A transporter sent to the far end of a
     * spur therefore keeps the whole spur to itself for as long as it is down there: another
     * transporter sent to the same place would have nowhere to pass, and the two would face each
     * other with neither able to move. Traffic merely passing *through* the mouth of the spur is
     * unaffected, which is the distinction that lets a spur keep a stopped transporter out of the
     * way without also stopping everyone else.
     */
    var spurReservation: GuidedTransporter? = null
        internal set

    private val myWaiters = mutableListOf<GuidedTransporter>()

    /**
     * Transporters waiting to enter this link, in the order they began waiting.
     *
     * A transporter can be held up by a link rather than by any particular zone: the zone it wants
     * may be free while the link as a whole is running the other way, or is a spur someone else is
     * down. What frees it is a change to the link, so it waits on the link.
     */
    val waiters: List<GuidedTransporter>
        get() = myWaiters

    /** How many transporters are waiting to enter this link. */
    val numWaiting: Int
        get() = myWaiters.size

    internal fun addWaiter(transporter: GuidedTransporter) {
        if (transporter !in myWaiters) myWaiters.add(transporter)
    }

    internal fun removeWaiter(transporter: GuidedTransporter) {
        myWaiters.remove(transporter)
    }

    /**
     * Whether a transporter may travel this link in the given direction.
     *
     * A one-way link admits only its own direction. A two-way link admits whichever direction is
     * already running on it, so that two transporters can never meet head on with no way past each
     * other. An empty two-way link admits either.
     */
    internal fun admitsDirection(forward: Boolean): Boolean {
        if (type == LinkType.UNIDIRECTIONAL) return forward
        val lock = directionLock ?: return true
        return lock.forward == forward
    }

    /** Takes or extends the hold on the direction of travel. */
    internal fun acquireDirection(forward: Boolean) {
        if (type == LinkType.UNIDIRECTIONAL) return
        val lock = directionLock
        directionLock = if (lock == null) {
            DirectionLock(forward, 1)
        } else {
            check(lock.forward == forward) {
                "Link ($name) is running ${if (lock.forward) "forward" else "backward"} and cannot " +
                        "also admit the other direction."
            }
            lock.copy(count = lock.count + 1)
        }
    }

    /**
     * Gives up one hold on the direction of travel, freeing the link when the last one goes.
     *
     * @return true when the link is now clear, so that anyone waiting to run the other way may be
     *   considered
     */
    internal fun releaseDirection(): Boolean {
        if (type == LinkType.UNIDIRECTIONAL) return false
        val lock = directionLock ?: return false
        directionLock = if (lock.count <= 1) null else lock.copy(count = lock.count - 1)
        return directionLock == null
    }

    /** Returns the link to its start-of-replication condition. */
    internal fun resetLink() {
        directionLock = null
        spurReservation = null
        myWaiters.clear()
    }

    /** The intersection a forward traversal enters, used when weighting the routing graph. */
    internal fun entered(forward: Boolean): GuidedPathNetwork.Intersection =
        if (forward) endIntersection else beginIntersection

    override fun toString(): String =
        "Link($name: ${beginIntersection.name} -> ${endIntersection.name}, type=$type, " +
                "length=$length, zones=$numZones x $zoneLength, velocityFactor=$velocityFactor)"
}
