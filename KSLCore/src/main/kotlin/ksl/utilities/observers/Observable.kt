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

/**  The Java observer/observable pattern has a number of flaws.  This class
 * provides a base implementation of the observer/observable pattern that
 * mitigates those flaws.  This allows observers to be added and called
 * in the order added to the component.  The basic usage of this class is to
 * have a class have an instance of Observable while implementing
 * the ObservableIfc.  The notifyObservers() method can be used to notify
 * attached observers whenever necessary.
 *
 * @author rossetti
 */
open class Observable<T> : ObservableIfc<T> {

    private val myObservers = mutableListOf<ObserverIfc<T>>()

    override fun attachObserver(observer: ObserverIfc<T>) {
        require(!isAttached(observer)) { "The supplied observer is already attached" }
        myObservers.add(observer)
    }

    override fun detachObserver(observer: ObserverIfc<T>) {
        myObservers.remove(observer)
    }

    override fun detachAllObservers() {
        myObservers.clear()
    }

    override fun isAttached(observer: ObserverIfc<T>): Boolean {
        return myObservers.contains(observer)
    }

    override fun countObservers(): Int {
        return myObservers.size
    }

    /** Notify the observers
     *
     * @param newValue
     */
    protected open fun notifyObservers(newValue: T) {
        for (o in myObservers) {
            o.onChange(newValue)
        }
    }
}

// Extension function so we don't need to instantiate IObserver
fun <T> Observable<T>.observe(block: (T?) -> Unit) {
    attachObserver(object : ObserverIfc<T> {
        override fun onChange(newValue: T) {
            block(newValue)
        }
    })
}