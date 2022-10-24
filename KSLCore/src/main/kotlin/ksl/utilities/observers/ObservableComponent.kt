/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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