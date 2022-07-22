package ksl.utilities.observers

/**
 *  https://in-kotlin.com/design-patterns/observer/
 */
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

interface DoubleChangedIfc {
    val changedSignal : Signal<Double>
}

class DoubleChanged : DoubleChangedIfc {
    override val changedSignal: Signal<Double> = Signal()
}

interface DoublePairChangedIfc {
    val changedSignal : Signal<Pair<Double, Double>>
}

class DoublePairChanged : DoublePairChangedIfc {
    override val changedSignal: Signal<Pair<Double, Double>> = Signal()
}