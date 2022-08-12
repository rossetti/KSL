package ksl.observers

import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.ObservableIfc
import ksl.utilities.observers.ObserverIfc

open class ModelElementObserver(observed: ModelElement, name: String?) : IdentityIfc by Identity(name),
    ObserverIfc<ModelElement.Status> {

    protected val observedModelElement: ModelElement = observed

    override fun update(theObserved: ObservableIfc<ModelElement.Status>, newValue: ModelElement.Status?) {

    }

    protected open fun beforeExperiment() {
    }

    protected open fun beforeReplication() {}

    protected open fun initialize() {}

    protected open fun montecarlo() {}

    protected open fun replicationEnded() {}

    protected open fun afterReplication() {}

    protected open fun update() {}

    protected open fun warmUp() {}

    protected open fun timedUpdate() {}

    protected open fun afterExperiment() {}

    protected open fun removedFromModel() {
    }
}