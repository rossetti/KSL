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
    fun attachObserver(observer: ObserverIfc<T>)

    /** Allows the deletion (removing) of an observer from the observable
     *
     * @param observer the observer to delete
     */
    fun detachObserver(observer: ObserverIfc<T>)

    /** Returns true if the observer is already attached
     *
     * @param observer the observer to check
     * @return true if attached
     */
    fun isAttached(observer: ObserverIfc<T>): Boolean

    /** Detaches all the observers from the observable
     *
     */
    fun detachAllObservers()

    /** Returns how many observers are currently attached to the observable
     *
     * @return number of observers
     */
    fun countObservers(): Int

//    /** Notify the observers
//     * @param newValue
//     */
//    fun notifyObservers(newValue: T)
}