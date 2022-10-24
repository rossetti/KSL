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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.simulation

import ksl.utilities.OrderedList
import ksl.utilities.exceptions.TooManyScansException

/**  Processes the ConditionalActions to check if their testCondition()
 * is true, if so the action is executed.  All actions are checked until
 * no action's testCondition() is true
 * To prevent conditional cycling the number of rescans is limited to
 * DEFAULT_MAX_SCANS, which can be changed by the user or turned off via
 * setMaxScanFlag()
 *
 * @author rossetti
 */
class ConditionalActionProcessor {

//TODO it would be good to add some logging

    /** Sets the maximum scan checking flag.  If true
     * the maximum number of scans is monitored during
     * the c phase
     */
    var maxScanFlag = true

    private var myActions: OrderedList<ConditionalAction> = OrderedList()

    /** Registers the action with the given priority
     *
     * @param action the action
     * @param priority the priority
     */
    internal fun register(action: ConditionalAction, priority: Int = DEFAULT_PRIORITY) {
        action.id = ++myActionCounter
        action.priority = priority
        myActions.add(action)
    }

    /** Changes the priority of a previously registered action
     *
     * @param action the action
     * @param priority the priority
     */
    internal fun changePriority(action: ConditionalAction, priority: Int) {
        unregister(action)
        action.priority = priority
        myActions.add(action)
    }

    /** Unregisters the action from the simulation
     *
     * @param action the action
     */
    internal fun unregister(action: ConditionalAction) {
        require(myActions.contains(action)) { "The supplied action is not registered" }
        myActions.remove(action)
    }

    /** Unregisters all actions that were previously registered.
     *
     */
    internal fun unregisterAllActions() {
        myActions.clear()
    }

    /** Returns true at least one ConditionalAction was executed
     * false means all actions tested false
     *
     * @return true if at least one
     */
    private fun executeConditionalActions(): Boolean {
        var test = false
        for (c in myActions) {
            if (c.testCondition()) {
                c.action()
                test = true
            }
        }
        return test
    }

    /** Sets the maximum number of scans
     */
    var maxScans: Int = DEFAULT_MAX_SCANS
        set(max) {
            require(max > 0) { "The max scans must be > 0" }
            field = max
        }

    internal fun performCPhase() {
        if (myActions.isEmpty()) {
            return
        }
        var test = true
        var i = 0
        while (test) {
            test = executeConditionalActions()
            i++
            if (maxScanFlag) {
                if (i >= maxScans) {
                    throw TooManyScansException()
                }
            }
        }
    }

    companion object {
        private var myActionCounter = 0
        const val DEFAULT_MAX_SCANS = 1000
        const val DEFAULT_PRIORITY = 1
    }
}