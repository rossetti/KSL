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

package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.DoublePairEmitter
import ksl.utilities.observers.DoublePairEmitterIfc
import ksl.utilities.observers.Observable

abstract class WeightedCollector(name: String? = null) : WeightedCollectorIfc, IdentityIfc by Identity(name),
    Observable<Pair<Double, Double>>(),
    DoublePairEmitterIfc by DoublePairEmitter() {

    override var lastValue = Double.NaN
        protected set

    var lastWeight = Double.NaN
        protected set

    override val weight: Double
        get() = lastWeight

    override var value: Double
        get() = lastValue
        set(value) {
            collect(value, 1.0)
        }

    override fun collect(obs: Double, weight: Double) {
        lastValue = obs
        lastWeight = weight
        notifyObservers(Pair(lastValue, lastWeight))
        emitter.emit(Pair(lastValue, lastWeight))
    }

    override fun reset() {
        lastValue = Double.NaN
        lastWeight = Double.NaN
    }
}