package ksl.utilities.observers

/**
 *  Permits observable pattern to be used in delegation pattern
 *  by exposing notification of observers via public method.
 */
class ObservableComponent<T> : Observable<T>() {

    /**
     *  Allows observable component to notify observers
     */
    fun notifyAttached(newValue: T) {
        super.notifyObservers(newValue)
    }
}

/**
 *  Another way to implement observable delegation. When
 *  the observed value property is changed, observers are
 *  notified.
 */
class ObservableValue<T>(initialValue: T) : Observable<T>() {
    // The real value of this observer
    // Doesn't need a custom getter, but the setter
    // we override to allow notifying all observers
    var observedValue: T = initialValue
        set(value) {
            field = value
            notifyObservers(field)
        }
}

fun main(){
    val c = ObservableComponent<String>()

    c.observe { println("Observer notified, new value = $it") }

    c.notifyAttached("something")

    val o = ObservableValue(0.0)

    o.observe { println("Observer notified, new value = $it") }

    o.observedValue = 33.0
}