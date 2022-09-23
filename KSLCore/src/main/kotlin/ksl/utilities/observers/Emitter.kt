package ksl.utilities.observers

/**
 *  https://in-kotlin.com/design-patterns/observer/
 */
class Emitter<TType> {
    class Connection
    private val callbacks = mutableMapOf<Connection, (TType) -> Unit>()
    var emissionsOn = true

    fun emit(newValue: TType) {
        if (!emissionsOn){
            return
        }
        for(cb in callbacks) {
            cb.value(newValue)
        }
    }

    fun attach(callback: (newValue: TType) -> Unit) : Connection {
        val connection = Connection()
        callbacks[connection] = callback
        return connection
    }

    fun detach(connection : Connection) {
        callbacks.remove(connection)
    }
}

interface DoubleEmitterIfc {
    val emitter : Emitter<Double>
}

class DoubleEmitter : DoubleEmitterIfc {
    override val emitter: Emitter<Double> = Emitter()
}

interface DoublePairEmitterIfc {
    val emitter : Emitter<Pair<Double, Double>>
}

class DoublePairEmitter : DoublePairEmitterIfc {
    override val emitter: Emitter<Pair<Double, Double>> = Emitter()
}