package ksl.observers

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.simulation.ModelElement.Status.*
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.ObserverIfc

/**
 *  Base class for reacting to status changes that occur on model elements.
 */
open class ModelElementObserver<T: ModelElement>(observed: T, name: String? = null) : IdentityIfc by Identity(name),
    ObserverIfc<ModelElement.Status> {

    protected val observedModelElement: T = observed
    protected val model: Model = observedModelElement.myModel

    override fun onChange(newValue: ModelElement.Status) {
        when(newValue){
            NONE -> nothing()
            BEFORE_EXPERIMENT -> beforeExperiment()
            BEFORE_REPLICATION -> beforeReplication()
            INITIALIZED -> initialize()
            CONDITIONAL_ACTION_REGISTRATION -> conditionalActionRegistered()
            MONTE_CARLO -> montecarlo()
            WARMUP ->  warmUp()
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

    protected open fun nothing(){}

    protected open fun beforeExperiment() {
    }

    protected open fun beforeReplication() {}

    protected open fun initialize() {}

    protected open fun conditionalActionRegistered(){}

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

    protected open fun elementRemoved(){

    }
}