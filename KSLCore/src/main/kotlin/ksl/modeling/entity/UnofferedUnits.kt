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

import ksl.simulation.Executive
import ksl.simulation.KSLEvent
import ksl.utilities.observers.ObserverIfc

/**
 *  Makes sure no units are left free beside a request that could take them. Every way a resource's units come free is
 *  meant to end in an offer (`Resource.offerFreedUnits`); a path that forgets leaves a request waiting beside idle
 *  units until the next release. Each change to a resource's units that leaves more of them available marks the
 *  resource here, and after the event, each marked resource offers its units if a request in one of its queues could
 *  take them now. In a model where every path offers, as the invariant checks verify after every event, it finds
 *  nothing to do and changes nothing.
 */
internal class UnofferedUnits(private val executive: Executive) : ObserverIfc<KSLEvent<*>?> {

    private val myMarked = LinkedHashSet<Resource>()

    /** Notes that a change to [resource] left more of its units available. */
    fun mark(resource: Resource) {
        myMarked.add(resource)
    }

    override fun onChange(newValue: KSLEvent<*>?) {
        if (executive.status != Executive.Status.AFTER_EVENT || myMarked.isEmpty()) return
        val marked = myMarked.toList()
        myMarked.clear()
        for (resource in marked) resource.offerUnitsLeftUnoffered()
    }
}
