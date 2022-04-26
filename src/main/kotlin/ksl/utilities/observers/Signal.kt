package ksl.utilities.observers

class Signal<TType> {
    class Connection
    val callbacks = mutableMapOf<Connection, (TType) -> Unit>()

    fun emit(newValue: TType) {
        for(cb in callbacks) {
            cb.value(newValue)
        }
    }

    fun connect(callback: (newValue: TType) -> Unit) : Connection {
        val connection = Connection()
        callbacks[connection] = callback
        return connection
    }

    fun disconnect(connection : Connection) {
        callbacks.remove(connection)
    }
}

interface DoubleValueChangedIfc {
    val doubleValueChangedSignal : Signal<Double>
}

class DoubleValueChanged : DoubleValueChangedIfc {
    override val doubleValueChangedSignal: Signal<Double> = Signal()
}