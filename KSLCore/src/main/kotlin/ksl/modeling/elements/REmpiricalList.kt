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

package ksl.modeling.elements

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.robj.DEmpiricalList
import ksl.utilities.random.robj.RElementIfc
import ksl.utilities.random.rvariable.KSLRandom

class REmpiricalList<T>(
    parent: ModelElement,
    dEmpiricalList: DEmpiricalList<T>,
    name: String? = null
) : ModelElement(parent, name), RandomElementIfc, RElementIfc<T> {

    constructor(
        parent: ModelElement,
        elements: List<T>,
        theCDF: DoubleArray,
        streamNumber: Int,
        name: String? = null
    ) : this(parent, DEmpiricalList<T>(elements, theCDF, streamNumber, parent.streamProvider), name)

    private val myDEmpirical = dEmpiricalList

    override val randomElement: T
        get() = myDEmpirical.randomElement

    /**
     * Controls whether warning of changing the initial random source during a replication
     * is logged, default is true.
     */
    var initialRandomSourceChangeWarning = true

    /**
     * before any replications make sure that the random source is using the initial random source
     */
    override fun beforeExperiment() {
        super.beforeExperiment()
        rnStream = initialStream
    }

    /**
     * after each replication check if random source changed during the replication and
     * if so, provide information to the user
     */
    override fun afterReplication() {
        super.afterReplication()
        if (rnStream !== initialStream) {
            // the random source or the initial random source references
            // were changed during the replication
            // make sure that the random source is the same
            // as the initial random source for the next replication
            rnStream = initialStream
            Model.logger.info { "The random stream of $name was changed back to the initial random stream after replication ${model.currentReplicationNumber}." }
        }
    }

    init {
        warmUpOption = false
        //TODO can this be moved into model? if so, where (cannot be in addToModelElementMap()) because that is in constructor
        // of the model element, which is called before this init block. this init block is called after the element has
        // been added to the model, upon creation of the element
        model.addStream(initialStream)
        RNStreamProvider.logger.info { "Initialized REmpiricalList(id = $id, name = ${this.name}) with stream id = ${initialStream.id}" }
    }
}