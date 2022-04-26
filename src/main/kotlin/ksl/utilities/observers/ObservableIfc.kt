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

import ksl.utilities.observers.ObserverIfc

/** The Java Observer/Observable implementation has a number of flaws.
 * This class represents an interface for objects that can be observed.
 * Essentially, observable objects promise the basic management of classes
 * that implement the ObserverIfc
 *
 * @author rossetti
 */
interface ObservableIfc<T> {
    /** Allows the adding (attaching) of an observer to the observable
     *
     * @param observer the observer to attach
     */
    fun attach(observer: ObserverIfc<T>)

    /** Allows the deletion (removing) of an observer from the observable
     *
     * @param observer the observer to delete
     */
    fun detach(observer: ObserverIfc<T>)

    /** Returns true if the observer is already attached
     *
     * @param observer the observer to check
     * @return true if attached
     */
    fun isAttached(observer: ObserverIfc<T>): Boolean

    /** Detaches all the observers from the observable
     *
     */
    fun detachAll()

    /** Returns how many observers are currently attached to the observable
     *
     * @return number of observers
     */
    fun countObservers(): Int

    /** Notify the observers
     *
     * @param theObserved
     * @param newValue
     */
    fun notifyObservers(theObserved: ObservableIfc<T>, newValue: T?)
}