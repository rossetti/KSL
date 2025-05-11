package ksl.modeling.variable

import ksl.utilities.IdentityIfc
import ksl.utilities.random.rng.StreamOptionIfc
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  While RandomVariable instances should in general be declared as private within model
 *  elements, this interface provides the modeler the ability to declare a public property
 *  that returns an instance with limited ability to change and use the underlying RandomVariable,
 *  prior to running the model.
 *
 *  For example:
 *
 *   private val myTBA = RandomVariable(this, ExponentialRV(6.0, 1))
 *   val tba: RandomVariableCIfc
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
interface RandomVariableCIfc : StreamOptionIfc, IdentityIfc {

    /**
     * Provides a reference to the underlying source of randomness
     * to initialize each replication.
     * Controls the underlying source for the RandomVariable. This is the
     * source to which each replication will be initialized.  This is only used
     * when the replication is initialized. Changing the reference has no effect
     * during a replication, since the random variable will continue to use
     * the reference returned by property randomSource.  Please also see the
     * discussion in the class documentation.
     *
     * The initial random source should not be changed while the model is running.
     */
    var initialRandomSource: RVariableIfc

    fun asString(): String

}