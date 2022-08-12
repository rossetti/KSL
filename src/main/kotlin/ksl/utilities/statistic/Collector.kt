package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.DoubleEmitter
import ksl.utilities.observers.DoubleEmitterIfc
import ksl.utilities.observers.Observable

abstract class Collector(name: String? = null) : CollectorIfc, IdentityIfc by Identity(name), Observable<Double>(),
    DoubleEmitterIfc by DoubleEmitter() {

    var lastValue = Double.NaN
        protected set

    override var value: Double
        get() = lastValue
        set(value) {
            collect(value)
        }

    override fun collect(obs: Double) {
        lastValue = obs
        notifyObservers(lastValue)
        emitter.emit(lastValue)
    }

    override fun reset() {
        lastValue = Double.NaN
    }
}