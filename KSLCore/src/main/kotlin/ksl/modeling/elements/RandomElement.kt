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

import ksl.modeling.variable.RandomSourceCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.StreamNumberIfc

abstract class RandomElement(
    parent: ModelElement,
    rSource: RandomIfc,
    name: String? = null
) : ModelElement(parent, name), RandomElementIfc, RandomSourceCIfc, StreamNumberIfc {

    /**
     * Provides a reference to the underlying source of randomness during the replication.
     * Controls the underlying RandomIfc source.  This
     * changes the source for the current replication only. The random
     * variable will start to use this source immediately; however if
     * a replication is started after this method is called, the random source
     * will be reassigned to the initial random source before the next replication
     * is executed.
     * To change the random source for the entire experiment (all replications)
     * use the initialRandomSource property
     */
    var randomSource: RandomIfc = rSource
        set(value) {
            field = if (value.streamProvider != streamProvider) {
                value.instance(value.streamNumber, streamProvider)
            } else {
                value
            }
        }

    init {
        warmUpOption = false
        this.randomSource = rSource
    }

    /**
     * Provides a reference to the underlying source of randomness to initialize each replication.
     * Controls the underlying RandomIfc source for the element. This is the
     * source to which each replication will be initialized.  This is only used
     * when the replication is initialized.
     *
     * You cannot change the initial random source while the model is running.
     */
    override var initialRandomSource: RandomIfc = randomSource
        set(value) {
            require(model.isNotRunning) {"The initial random source cannot be changed during a replication"}
            field = if (value.streamProvider == streamProvider){
                value
            } else {
                value.instance(value.streamNumber, streamProvider)
            }
        }

    override val streamNumber: Int
        get() = randomSource.streamNumber

    override fun resetStartStream() {
        initialRandomSource.resetStartStream()
    }

    override fun resetStartSubStream() {
        initialRandomSource.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        initialRandomSource.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = initialRandomSource.antithetic
        set(value) {
            initialRandomSource.antithetic = value
        }

    override var advanceToNextSubStreamOption: Boolean
        get() = initialRandomSource.advanceToNextSubStreamOption
        set(value) {
            initialRandomSource.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = initialRandomSource.resetStartStreamOption
        set(value) {
            initialRandomSource.resetStartStreamOption = value
        }

    override fun asString(): String {
        return toString()
    }

    /**
     * before any replications make sure that the random source is using the initial random source
     */
    override fun beforeExperiment() {
        super.beforeExperiment()
        randomSource = initialRandomSource
    }

    /**
     * after each replication check if random source changed during the replication and
     * if so, provide information to the user
     */
    override fun afterReplication() {
        super.afterReplication()
        if (randomSource != initialRandomSource) {
            // the random source or the initial random source references
            // were changed during the replication
            // make sure that the random source is the same
            // as the initial random source for the next replication
            randomSource = initialRandomSource
            Model.logger.info { "The random source of $name was changed back to the initial random source after replication ${model.currentReplicationNumber}." }
        }
    }

}