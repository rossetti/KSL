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

package ksl.modeling.variable

import ksl.modeling.elements.RandomElementIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.PreviousValueIfc
import ksl.utilities.random.SampleIfc
import ksl.utilities.random.StreamNumberIfc
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A random variable (RandomVariable) is a function that maps a probability space to a real number.
 * A random variable uses a RandomIfc to provide the underlying mapping to a real number via the value() method.
 * <p>
 * To construct a RandomVariable the user must provide an instance of a class that implements the RandomIfc interface as the initial random source.
 * This source is used to initialize the source of randomness for each replication.
 * <p>
 * WARNING:  For efficiency, this class uses a direct reference to the supplied initial random source.
 * It simply wraps the supplied object reference to a random source so that it can be utilized within
 * the KSL model.  Because of the direct reference to the random source, a change to the state of the
 * random source will be reflected in the use of that instance within this class.  Thus, mutating
 * the state of the  random source will also see those mutations reflected in the usage of this
 * class.  This may or not be what is expected by the client.  For example, mutating the state of
 * the initial random source during a replication may cause each replication to start with different initial
 * conditions.
 * <p>
 * Using the randomSource property allows the user to change the source of randomness during a replication.
 * The source of randomness during a replication is set to the reference of the initial
 * random source prior to running any replications.  This ensures that each replication uses
 * the same random source during the replication, unless the random source is changed during
 * a replication. However, the user may use the randomSource property
 * to immediately change the source of randomness during the replication. This change is in effect only during
 * the current replication.  After each replication, the source of randomness is set back to
 * the reference to the initial random source.  This ensures that each replication starts off using the same random source.
 * For this reason, the use of initialRandomSource property should be limited to before or after
 * running a simulation experiment.
 * <p>
 * The initial source is used to set up the source used during the replication.  If the
 * client changes the reference to the initial source, this change does not become effective
 * until the beginning of the next replication.  In other words, the random source used
 * during the replication is unaffected. However, the client might change the initial random source
 * during a replication.  If this occurs, the change happens but the replication will continue to use its current
 * random source as defined by the randomSource property. The change in the initial random source does
 * not really take effect until the beginning of the NEXT replication. Again, mutating the initial random source during a replication is
 * generally a bad idea unless you really know what you are doing.
 * <p>
 * Changing the initial random source between experiments (simulation runs) is very common.  For example, to set up an experiment
 * that has different random characteristics the client can and should change the initial source of randomness
 * (either by mutating the initial random source or by supplying a reference to a different initial random source).
 *
 * To facilitate the synchronization of random number streams, the underlying random number stream will automatically
 * be advanced to its next sub-stream after each replication.  This occurs by default unless the resetNextSubStreamOption
 * is set to false.
 */
class RandomVariable(
    parent: ModelElement,
    rSource: RVariableIfc,
    name: String? = null
) : ModelElement(parent, name), RandomElementIfc,
    RandomVariableCIfc, StreamNumberIfc, SampleIfc, GetValueIfc, PreviousValueIfc {

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
    var randomSource: RVariableIfc = rSource
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
     * when the replication is initialized. Changing the reference has no effect
     * during a replication.
     *
     * The initial random source cannot be changed while the model is running.
     */
    override var initialRandomSource: RVariableIfc = randomSource
        set(value) {
            require(model.isNotRunning) {"The initial random source cannot be changed during a replication"}
            field = if (value.streamProvider == streamProvider){
                value
            } else {
                value.instance(value.streamNumber, streamProvider)
            }
        }

    override fun sample(): Double {
        return randomSource.sample()
    }

    override fun value(): Double {
        previousValue = randomSource.value
        notifyModelElementObservers(Status.UPDATE)
        return previousValue
    }

    override var previousValue: Double = 0.0
        protected set

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
        if (randomSource !== initialRandomSource) {
            // the random source or the initial random source references
            // were changed during the replication
            // make sure that the random source is the same
            // as the initial random source for the next replication
            randomSource = initialRandomSource
            Model.logger.info { "The random source of $name was changed back to the initial random source after replication ${model.currentReplicationNumber}." }
        }
    }

    override fun asString(): String {
        val sb = StringBuilder()
        sb.append(toString())
        sb.append(", ")
        sb.append(randomSource.toString())
        return sb.toString()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(super.toString())
        sb.append("Initial random Source: $initialRandomSource with stream ${initialRandomSource.streamNumber}")
        return sb.toString()
    }

}