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

package ksl.modeling.variable

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.StreamNumberIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.robj.BernoulliPickerIfc
import ksl.utilities.random.rvariable.BernoulliRV

interface BernoulliVariableCIfc {
    /**
     * Provides a reference to the underlying source of randomness to initialize each replication.
     * Controls the underlying BernoulliRV source for the element. This is the
     * source to which each replication will be initialized.  This is only used
     * when the replication is initialized. Changing the reference has no effect
     * during a replication.
     *
     * This cannot be used during a replication
     */
    var initialBernoulliRV: BernoulliRV
}

/**
 *  A BernoulliVariable models two choices ([success], [failure]) governed
 *   by a Bernoulli random variable
 *   @param parent the parent model element
 *   @param bernoulliRV the Bernoulli random variable with success probability mapped to [success].
 *   @param success the thing associated with success. Must have the same type as failure.
 *   @param failure the thing associated with failure. Must have the same type as success.
 *   @param name the name of the model element
 */
class BernoulliVariable<T> @JvmOverloads constructor(
    parent: ModelElement,
    bernoulliRV: BernoulliRV,
    override val success: T,
    override val failure: T,
    name: String? = null
) : ModelElement(parent, name), BernoulliPickerIfc<T>,
    RNStreamControlIfc, StreamNumberIfc, BernoulliVariableCIfc {

    init {
        require(success != failure) { "The success and failure options cannot be the same." }
    }

    /**
     *  A BernoulliVariable models two choices ([success], [failure]) governed
     *   by a Bernoulli random variable
     *   @param parent the parent model element
     *   @param successProbability the success probability mapped to [success].
     *   @param success the thing associated with success. Must have the same type as failure.
     *   @param failure the thing associated with failure. Must have the same type as success.
     *   @param streamNumber the desired stream number from the model's provider
     *   @param name the name of the model element
     */
    @Suppress("unused")
    @JvmOverloads
    constructor(
        parent: ModelElement,
        successProbability: Double,
        success: T,
        failure: T,
        streamNumber: Int = 0,
        name: String? = null
    ): this(parent, BernoulliRV(successProbability, streamNumber, parent.streamProvider), success, failure, name)

    /**
     * Provides a reference to the underlying source of randomness during the replication.
     * Controls the underlying BernoulliRV source.  This
     * changes the source for the current replication only. The random
     * variable will start to use this source immediately; however, if
     * a replication is started after this method is called, the random source
     * will be reassigned to the initial random source before the next replication
     * is executed.
     * To change the random source for the entire experiment (all replications)
     * use the initialRandomSource property
     */
    var bernoulliRV: BernoulliRV = bernoulliRV
        set(value) {
            field = if (value.streamProvider != streamProvider) {
                value.instance(value.streamNumber, streamProvider)
            } else {
                value
            }
        }

    init {
        warmUpOption = false
        this.bernoulliRV = bernoulliRV
    }

    /**
     * Provides a reference to the underlying source of randomness to initialize each replication.
     * Controls the underlying BernoulliRV source for the element. This is the
     * source to which each replication will be initialized.  This is only used
     * when the replication is initialized. Changing the reference has no effect
     * during a replication.
     *
     * This cannot be used during a replication
     */
    override var initialBernoulliRV: BernoulliRV = bernoulliRV
        set(value) {
            require(model.isNotRunning) {"The initial Bernoulli source cannot be changed during a replication"}
            field = if (value.streamProvider == streamProvider){
                value
            } else {
                value.instance(value.streamNumber, streamProvider)
            }
        }

    /** Returns a randomly selected value
     */
    override val randomElement: T
        get() = if (bernoulliRV.boolValue) success else failure

    override val streamNumber: Int
        get() = initialBernoulliRV.streamNumber

    override fun resetStartStream() {
        initialBernoulliRV.resetStartStream()
    }

    override fun resetStartSubStream() {
        initialBernoulliRV.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        initialBernoulliRV.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = initialBernoulliRV.antithetic
        set(value) {
            initialBernoulliRV.antithetic = value
        }

    override var advanceToNextSubStreamOption: Boolean
        get() = initialBernoulliRV.advanceToNextSubStreamOption
        set(value) {
            initialBernoulliRV.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = initialBernoulliRV.resetStartStreamOption
        set(value) {
            initialBernoulliRV.resetStartStreamOption = value
        }

    /**
     * before any replications make sure that the random source is using the initial random source
     */
    override fun beforeExperiment() {
        super.beforeExperiment()
        bernoulliRV = initialBernoulliRV
    }

    /**
     * After each replication, check if the random source changed during the replication and
     * if so, provide information to the user.
     */
    override fun afterReplication() {
        super.afterReplication()
        if (bernoulliRV !== initialBernoulliRV) {
            // the random source or the initial random source references
            // were changed during the replication
            // make sure that the random source is the same
            // as the initial random source for the next replication
            bernoulliRV = initialBernoulliRV
            Model.logger.info { "The random source of $name was changed back to the initial random source after replication ${model.currentReplicationNumber}." }
        }
    }
}