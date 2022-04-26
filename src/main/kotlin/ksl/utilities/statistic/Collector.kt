package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.DoubleValueChanged
import ksl.utilities.observers.DoubleValueChangedIfc
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc

abstract class Collector(name: String? = null) : CollectorIfc, IdentityIfc by Identity(name), ObservableIfc<Double> by Observable(),
    DoubleValueChangedIfc by DoubleValueChanged() {

    var lastValue = Double.NaN
        protected set

    override var value: Double
        get() = lastValue
        set(value) {
            collect(value)
        }

    override fun collect(value: Double) {
        lastValue = value
        notifyObservers(this, value)
        doubleValueChangedSignal.emit(value)
    }

    override fun reset() {
        lastValue = Double.NaN
    }
}