/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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