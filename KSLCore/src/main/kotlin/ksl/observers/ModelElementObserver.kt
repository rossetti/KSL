package ksl.observers

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.simulation.ModelElement.Status.*
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.ObserverIfc

/**
 *  Base class for reacting to status changes that occur on a model element.  This observer is meant to observe
 *  1 and only 1 model element.  However, a model element may have many observers.
 */
abstract class ModelElementObserver(name: String? = null) : IdentityIfc by Identity(name) {

    protected var observedModelElement: ModelElement? = null

    val isAttached: Boolean
        get() = observedModelElement != null

    val isNotAttached: Boolean
        get() = !isAttached

    fun attach(observed: ModelElement) {
        if (isNotAttached) {
            observedModelElement = observed
            observed.attachModelElementObserver(this)
        } else {
            require(observedModelElement == observed) { "Attempted to attach to ${observed.name} when already attached to ${observedModelElement!!.name}" }
        }
    }

    fun detach() {
        require(isAttached) { "Attempted to detach a model element observer that was not attached." }
        observedModelElement!!.detachModelElementObserver(this)
        observedModelElement = null
    }

    internal fun onChange(newValue: ModelElement.Status) {
        when (newValue) {
            NONE -> nothing()
            BEFORE_EXPERIMENT -> beforeExperiment()
            BEFORE_REPLICATION -> beforeReplication()
            INITIALIZED -> initialize()
            CONDITIONAL_ACTION_REGISTRATION -> conditionalActionRegistered()
            MONTE_CARLO -> montecarlo()
            WARMUP -> warmUp()
            UPDATE -> update()
            TIMED_UPDATE -> timedUpdate()
            REPLICATION_ENDED -> replicationEnded()
            AFTER_REPLICATION -> afterReplication()
            AFTER_EXPERIMENT -> afterExperiment()
            MODEL_ELEMENT_ADDED -> elementAdded()
            MODEL_ELEMENT_REMOVED -> elementRemoved()
            REMOVED_FROM_MODEL -> removedFromModel()
        }
    }

    protected open fun nothing() {}

    protected open fun beforeExperiment() {
    }

    protected open fun beforeReplication() {}

    protected open fun initialize() {}

    protected open fun conditionalActionRegistered() {}

    protected open fun montecarlo() {}

    protected open fun replicationEnded() {}

    protected open fun afterReplication() {}

    protected open fun update() {}

    protected open fun warmUp() {}

    protected open fun timedUpdate() {}

    protected open fun afterExperiment() {}

    protected open fun removedFromModel() {
    }

    protected open fun elementAdded() {
    }

    protected open fun elementRemoved() {
    }
}