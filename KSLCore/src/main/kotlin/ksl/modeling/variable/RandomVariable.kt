package ksl.modeling.variable

import ksl.modeling.elements.RandomElementIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider

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
interface RandomVariableCIfc {
    var resetNextSubStreamOption: Boolean
    var resetStartStreamOption: Boolean

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
 * the JSL model.  Because of the direct reference to the random source, a change to the state of the
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
class RandomVariable(parent: ModelElement, rSource: RandomIfc, name: String? = null) : ModelElement(parent, name),
    RandomIfc, RandomElementIfc, RandomVariableCIfc {

    //TODO there is no capturing of the response implemented like in the JSL

    override var resetNextSubStreamOption: Boolean = true

    override var resetStartStreamOption: Boolean = true

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
    override var initialRandomSource: RandomIfc = rSource
        set(value) {
            if (model.isRunning){
                if (initialRandomSourceChangeWarning){
                    Model.logger.warn {"Changed the initial random source of $name during replication ${model.currentReplicationNumber}."}
                }
            }
            field = value
        }

    /**
     * RandomIfc provides a reference to the underlying source of randomness
     * during the replication.
     * Controls the underlying RandomIfc source for the RandomVariable.  This
     * changes the source for the current replication only. The random
     * variable will start to use this source immediately; however if
     * a replication is started after this method is called, the random source
     * will be reassigned to the initial random source before the next replication
     * is executed.
     * <p>
     * To change the random source for the entire experiment (all replications)
     * use the initialRandomSource property
     */
    var randomSource: RandomIfc = initialRandomSource

    override var rnStream: RNStreamIfc
        get() = randomSource.rnStream
        set(value) {
            randomSource.rnStream = value
        }

    /**
     * Controls whether warning of changing the initial random source during a replication
     * is logged, default is true.
     */
    override var initialRandomSourceChangeWarning = true

    init {
        warmUpOption = false
    }

    override fun sample(): Double {
        return randomSource.sample()
    }

    override fun value(): Double {
        return randomSource.value
    }

    /**
     * before any replications reset the underlying random number generator to the
     * starting stream
     */
    override fun beforeExperiment() {
        super.beforeExperiment()
        randomSource = initialRandomSource
        if (resetStartStreamOption) {
            resetStartStream()
        }
    }

    /**
     * after each replication reset the underlying random number generator to the next
     * sub-stream
     */
    override fun afterReplication() {
        super.afterReplication()
        if (randomSource !== initialRandomSource) {
            // the random source or the initial random source references
            // were changed during the replication
            // make sure that the random source is the same
            // as the initial random source for the next replication
            randomSource = initialRandomSource
            Model.logger.info {"The random source of $name was changed back to the initial random source after replication ${model.currentReplicationNumber}."}
        }
        if (resetNextSubStreamOption) {
            advanceToNextSubStream()
        }
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
    init {
        RNStreamProvider.logger.trace { "Initialized RandomVariable: $this" }
    }
}