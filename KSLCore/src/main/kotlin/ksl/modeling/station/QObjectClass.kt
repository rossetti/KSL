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

import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/**
 *  A named template for a class of QObject instances flowing through a
 *  [StationNetwork]. A class is a configuration, not a factory: instances are
 *  created by a [SourceStation] and then have the class's template applied to
 *  them. This is the bridge to multi-class queueing networks, where each class
 *  can carry its own type id, priority, service-time provider, and route.
 *
 *  @param className a name for the class, unique within a network; used to label
 *  per-class responses
 *  @param typeId the integer type id stamped onto each instance
 *  ([ModelElement.QObject.qObjectType]); must be unique per class within a network
 *  @param priority the priority assigned to each instance
 *  @param valueObject an optional per-instance value (for example, the class's own
 *  service-time random variable); a station with `useQObjectForActivityTime`
 *  enabled uses this for the activity time
 *  @param route an optional default route attached to each instance so the class
 *  follows its own path through the network
 *  @param configure an optional action applied last for any additional setup
 */
class QObjectClass(
    val className: String,
    val typeId: Int,
    val priority: Int = DEFAULT_PRIORITY,
    val valueObject: GetValueIfc? = null,
    val route: Route? = null,
    val configure: ((ModelElement.QObject) -> Unit)? = null
) {
    init {
        require(className.isNotBlank()) { "A QObjectClass name must not be blank." }
    }

    /**
     *  Applies this class's template to a freshly created [qObject]: stamps the
     *  type id and priority, attaches the value object and route (if any), and
     *  runs the optional [configure] action.
     */
    fun apply(qObject: ModelElement.QObject) {
        qObject.qObjectType = typeId
        qObject.priority = priority
        valueObject?.let { qObject.valueObject = it }
        route?.let { qObject.sender(it.newSender()) }
        configure?.invoke(qObject)
    }

    companion object {
        const val DEFAULT_PRIORITY: Int = 1
    }
}
