/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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