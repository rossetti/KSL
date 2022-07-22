package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.*

abstract class Collector(name: String? = null) : CollectorIfc, IdentityIfc by Identity(name),
    ObservableIfc<Double> by Observable(),
    DoubleChangedIfc by DoubleChanged() {

    var lastValue = Double.NaN
        protected set

    override var value: Double
        get() = lastValue
        set(value) {
            collect(value)
        }

    override fun collect(obs: Double) {
        lastValue = obs
        notifyObservers(this, lastValue)
        emitter.emit(lastValue)
    }

    override fun reset() {
        lastValue = Double.NaN
    }
}