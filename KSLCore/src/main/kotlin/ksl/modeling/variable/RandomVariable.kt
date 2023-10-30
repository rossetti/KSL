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

import ksl.modeling.elements.RandomElement
import ksl.simulation.ModelElement
import ksl.utilities.IdentityIfc
import ksl.utilities.PreviousValueIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.StreamOptionIfc

/**
 *  While RandomVariable instances should in general be declared as private within model
 *  elements, this interface provides the modeler the ability to declare a public property
 *  that returns an instance with limited ability to change and use the underlying RandomVariable,
 *  prior to running the model.
 *
 *  For example:
 *
 *   private val myTBA = RandomVariable(this, ExponentialRV(6.0, 1))
 *   val tba: RandomSourceCIfc
 *      get() = myTBA
 *
 *   Then users of the public property can change the initial random source and do other
 *   controlled changes without fully exposing the private variable.  The implementer of the
 *   model element that contains the private random variable does not have to write additional
 *   functions to control the random variable and can use this strategy to expose what is needed.
 *   This is most relevant to setting up the model elements prior to running the model or
 *   accessing information after the model has been executed. Changes or use during a model
 *   run is readily available through the general interface presented by RandomVariable.
 *
 *   The naming convention "CIfc" is used to denote controlled interface.
 *
 */
interface RandomSourceCIfc : StreamOptionIfc, IdentityIfc {

    /**
     * RandomIfc provides a reference to the underlying source of randomness
     * to initialize each replication.
     * Controls the underlying RandomIfc source for the RandomVariable. This is the
     * source to which each replication will be initialized.  This is only used
     * when the replication is initialized. Changing the reference has no effect
     * during a replication, since the random variable will continue to use
     * the reference returned by property randomSource.  Please also see the
     * discussion in the class documentation.
     * <p>
     * WARNING: If this is used during an experiment to change the characteristics of
     * the random source, then each replication may not necessarily start in the
     * same initial state.  It is recommended that this be used only prior to executing experiments.
     */
    var initialRandomSource: RandomIfc

    var rnStream: RNStreamIfc

    /**
     * Controls whether warning of changing the initial random source during a replication
     * is logged, default is true.
     */
    var initialRandomSourceChangeWarning: Boolean

    fun asString(): String
}

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
open class RandomVariable(
    parent: ModelElement,
    rSource: RandomIfc,
    name: String? = null
) : RandomElement(parent, rSource, name), RandomIfc, PreviousValueIfc {

    //the calls to super<RandomElement> are because both RandomElementIfc and RandomIfc implement
    // common interfaces

    final override fun sample(): Double {
        return randomSource.sample()
    }

    final override fun value(): Double {
        previousValue = randomSource.value
        notifyModelElementObservers(Status.UPDATE)
        return previousValue
    }

    override var previousValue: Double = 0.0
        protected set

    final override fun resetStartStream() {
        super<RandomElement>.resetStartStream()
    }

    final override fun resetStartSubStream() {
        super<RandomElement>.resetStartSubStream()
    }

    final override fun advanceToNextSubStream() {
        super<RandomElement>.advanceToNextSubStream()
    }

    final override var antithetic: Boolean
        get() = super<RandomElement>.antithetic
        set(value) {
            super<RandomElement>.antithetic = value
        }

    final override var advanceToNextSubStreamOption: Boolean
        get() = super<RandomElement>.advanceToNextSubStreamOption
        set(value) {
            super<RandomElement>.advanceToNextSubStreamOption = value
        }

    final override var resetStartStreamOption: Boolean
        get() = super<RandomElement>.resetStartStreamOption
        set(value) {
            super<RandomElement>.resetStartStreamOption = value
        }

    override fun asString(): String {
        val sb = StringBuilder()
        sb.append(toString())
        sb.append(randomSource.toString())
        return sb.toString()
    }

    override fun toString(): String {
        return super.toString() + " with stream ${randomSource.rnStream.id}"
    }

}