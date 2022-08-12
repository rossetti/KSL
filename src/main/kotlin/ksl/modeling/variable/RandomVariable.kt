package ksl.modeling.variable

import ksl.modeling.elements.RandomElementIfc
import ksl.simulation.ModelElement
import ksl.simulation.Simulation
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc

class RandomVariable(parent: ModelElement, rSource: RandomIfc, name: String? = null) : ModelElement(parent, name),
    RandomIfc, RandomElementIfc {
    //TODO there is no capturing of the response implemented like in the JSL
    override var resetNextSubStreamOption: Boolean = true

    override var resetStartStreamOption: Boolean = true

    /**
     * RandomIfc provides a reference to the underlying source of randomness
     * to initialize each replication.
     */
    var initialRandomSource: RandomIfc = rSource
        set(value) {
            if (simulation.isRunning){
                if (initialRandomSourceChangeWarning){
                    Simulation.logger.warn {"Changed the initial random source of $name during replication $currentReplicationNumber."}
                }
            }
            field = value
        }

    /**
     * RandomIfc provides a reference to the underlying source of randomness
     * during the replication
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
    var initialRandomSourceChangeWarning = true

    init {
        myWarmUpOption = false
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
            Simulation.logger.info {"The random source of $name was changed back to the initial random source after replication $currentReplicationNumber."}
        }
        if (resetNextSubStreamOption) {
            advanceToNextSubStream()
        }
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append(toString())
        sb.append(randomSource.toString())
        return sb.toString()
    }

}