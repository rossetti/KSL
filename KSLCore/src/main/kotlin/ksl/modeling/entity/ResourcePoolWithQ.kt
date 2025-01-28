/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement

open class ResourcePoolWithQ(
    parent: ModelElement,
    resources: List<Resource>,
    queue: RequestQ? = null,
    name: String? = null
) : ResourcePool(parent, resources, name) {
    /**
     * Holds the entities that are waiting for allocations of the resource's units in the pool
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")
    init {
        for (resource in resources) {
            resource.registerCapacityChangeQueue(myWaitingQ)
        }
    }

    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    val resourcesWithQ: List<ResourceWithQCIfc>
        get() {
            val list = mutableListOf<ResourceWithQCIfc>()
            for(resource in myResources){
                if (resource is ResourceWithQ){
                    list.add(resource)
                }
            }
            return list
        }


}