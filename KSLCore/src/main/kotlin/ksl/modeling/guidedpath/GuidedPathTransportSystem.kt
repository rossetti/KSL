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

import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement

/**
 * A guide path an entity drives itself over: the space layer, plus the passive protocol's own
 * accounting.
 *
 * Almost everything a modeller reaches for is inherited from [GuidedPathSpace] -- the zones, the
 * fleet, the movement engine, the invariant checking, the congestion statistics and the five
 * per-carry figures -- because almost all of it is about the guide path rather than about who
 * decided to send a vehicle down it. What is added here is the one quantity that belongs to *this*
 * way of modelling and to no other.
 *
 * ## Why `transportTime` is the only thing this adds
 *
 * The two subsystems both have something they call a transport time, and they do not mean the same
 * thing by it. Here it runs from the entity's **request** to the entity being set down, because in
 * this paradigm the request is the beginning of the story: the entity asks, waits for a transporter,
 * rides it, and gets off. The active subsystem's runs from the load being **aboard** to being set
 * down, because there the wait is decomposed into waiting for a decision and waiting for a vehicle,
 * and rolling those back into one figure would throw away the decomposition that subsystem exists to
 * provide.
 *
 * Two rows that mean two things are better than one row that means either, so each paradigm
 * publishes its own and neither inherits the other's. That is the whole of the difference, and the
 * smallness of it is the point: the paradigms differ in how work is *asked for*, not in how a
 * vehicle crosses a zone.
 *
 * ## Compatibility
 *
 * Nothing about this type's construction, name or reported rows changed when the space layer was
 * lifted out of it. A model that said `GuidedPathTransportSystem(this, network, name = "Sys")`
 * before says it now, and its report carries the same rows under the same names.
 *
 * @param parent the containing model element
 * @param network the guide path to operate, already built
 * @param name a name for the system
 */
open class GuidedPathTransportSystem @JvmOverloads constructor(
    parent: ModelElement,
    network: GuidedPathNetwork,
    zoneContentionRule: ZoneContentionRuleIfc = FIFOZoneContentionRule(),
    collectLinkStatistics: Boolean = false,
    collectZoneStatistics: Boolean = false,
    name: String? = null
) : GuidedPathSpace(
    parent, network, zoneContentionRule, collectLinkStatistics, collectZoneStatistics, name
) {

    private val myTransportTime = Response(this, name = "${this.name}:TransportTime")

    /** How long a whole transport took, from the request to the entity being set down. */
    val transportTime: ResponseCIfc
        get() = myTransportTime

    /**
     * Records a completed transport. Called by the process verb that finishes one.
     *
     * The five figures go to the space layer's paradigm-neutral seam, which the active subsystem
     * feeds as well; only the total is this paradigm's own.
     */
    internal fun collectTransportResult(result: GuidedTransportResult) {
        myTransportTime.value = result.totalTime
        collectCarry(
            result.approachTime, result.rideTime, result.blockedTime,
            result.zonesTraversed, result.routeLength
        )
    }

    companion object {

        /**
         * The system property that switches invariant checking on for every guide path in this JVM.
         *
         * It belongs to [GuidedPathSpace], which is where the control it names lives, and is
         * re-exported here because Kotlin companions are not inherited. Prefer
         * `GuidedPathSpace.CHECK_INVARIANTS_PROPERTY`; the two are the same string.
         */
        const val CHECK_INVARIANTS_PROPERTY: String = GuidedPathSpace.CHECK_INVARIANTS_PROPERTY
    }
}
