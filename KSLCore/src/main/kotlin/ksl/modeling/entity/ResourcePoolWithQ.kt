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

class ResourcePoolWithQ(
    parent: ModelElement,
    resources: List<Resource>,
    queue: RequestQ? = null,
    name: String? = null
) : ResourcePool(parent, resources, name) {
    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ

    init {
        myWaitingQ = queue ?: RequestQ(this, "${this.name}:Q")
    }

    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    /** Makes the specified number of single unit resources and includes them in the pool.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        queue: RequestQ? = null,
        name: String? = null
    ) : this(
        parent,
        mutableListOf(),
        queue,
        name
    ) {
        for (i in 1..numResources) {
            addResource(Resource(this, "${this.name}:R${i}"))
        }
    }

}