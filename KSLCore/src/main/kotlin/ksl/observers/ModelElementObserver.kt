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

    internal fun onChange(modelElement: ModelElement, newValue: ModelElement.Status) {
        when (newValue) {
            NONE -> nothing(modelElement)
            BEFORE_EXPERIMENT -> beforeExperiment(modelElement)
            BEFORE_REPLICATION -> beforeReplication(modelElement)
            INITIALIZED -> initialize(modelElement)
            CONDITIONAL_ACTION_REGISTRATION -> conditionalActionRegistered(modelElement)
            MONTE_CARLO -> montecarlo(modelElement)
            WARMUP -> warmUp(modelElement)
            UPDATE -> update(modelElement)
            TIMED_UPDATE -> timedUpdate(modelElement)
            REPLICATION_ENDED -> replicationEnded(modelElement)
            AFTER_REPLICATION -> afterReplication(modelElement)
            AFTER_EXPERIMENT -> afterExperiment(modelElement)
            MODEL_ELEMENT_ADDED -> elementAdded(modelElement)
            MODEL_ELEMENT_REMOVED -> elementRemoved(modelElement)
            REMOVED_FROM_MODEL -> removedFromModel(modelElement)
        }
    }

    protected open fun nothing(modelElement: ModelElement) {}

    protected open fun beforeExperiment(modelElement: ModelElement) {
    }

    protected open fun beforeReplication(modelElement: ModelElement) {}

    protected open fun initialize(modelElement: ModelElement) {}

    protected open fun conditionalActionRegistered(modelElement: ModelElement) {}

    protected open fun montecarlo(modelElement: ModelElement) {}

    protected open fun replicationEnded(modelElement: ModelElement) {}

    protected open fun afterReplication(modelElement: ModelElement) {}

    protected open fun update(modelElement: ModelElement) {}

    protected open fun warmUp(modelElement: ModelElement) {}

    protected open fun timedUpdate(modelElement: ModelElement) {}

    protected open fun afterExperiment(modelElement: ModelElement) {}

    protected open fun removedFromModel(modelElement: ModelElement) {
    }

    protected open fun elementAdded(modelElement: ModelElement) {
    }

    protected open fun elementRemoved(modelElement: ModelElement) {
    }
}