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
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")

    init {
        //TODO make this check unnecessary
        for(resource in myResources){
            if (resource is ResourceWithQ){
                require(resource.waitingQ == myWaitingQ) {"ResourceWithQ instance: ${resource.name} did not have the same queue (${resource.myWaitingQ.name}) as the pool queue: ${myWaitingQ.name}."}
            }
        }
    }

    fun addResource(resource: ResourceWithQ) {
        //TODO make this check unnecessary
        require(resource.waitingQ == myWaitingQ) {"ResourceWithQ instance: ${resource.name} did not have the same queue as the pool."}
        super.addResource(resource)
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

    /** Makes the specified number of single unit resources and includes them in the pool.
     *  The pool is configured with a queue and each created resource is a ResourceWithQ that
     *  uses the pool's queue.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        name: String? = null
    ) : this(parent, mutableListOf(), null, name) {
        require(numResources >= 1) {"There must be 1 or more resources to create when creating ${this.name}"}
        for (i in 1..numResources) {
            addResource(ResourceWithQ(this, queue = myWaitingQ, name = "${this.name}:R${i}"))
        }
    }

    /** Makes the specified number of single unit resources and includes them in the pool.
     *  The pool is configured with the supplied queue and each create resource is a ResourceWithQ that
     *  uses the supplied queue.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        queue: RequestQ,
        name: String? = null
    ) : this(
        parent,
        mutableListOf(),
        queue,
        name
    ) {
        for (i in 1..numResources) {
            addResource(ResourceWithQ(this, queue = queue, name = "${this.name}:R${i}"))
        }
    }

}