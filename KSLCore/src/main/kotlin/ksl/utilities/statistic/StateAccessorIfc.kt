/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
package ksl.utilities.statistic

import ksl.utilities.IdentityIfc


/**
 *
 */
interface StateAccessorIfc : IdentityIfc {
    /**
     * Gets whether the state has been entered
     *
     * @return True means that the state has been entered
     */
    val isEntered: Boolean

    /**
     * Gets the time that the state was last entered
     *
     * @return A double representing the time that the state was last entered
     */
    val timeStateEntered: Double

    /**
     * Gets the time that the state was last exited
     *
     * @return A double representing the time that the state was last exited
     */
    val timeStateExited: Double

    /**
     * time that the state was entered for the first time
     */
    val timeFirstEntered: Double

    /**
     * Gets the number of times the state was entered
     *
     * @return A double representing the number of times entered
     */
    val numberOfTimesEntered: Double

    /**
     * Gets the number of times the state was exited
     *
     * @return A double representing the number of times exited
     */
    val numberOfTimesExited: Double

    /**
     * Gets a statistic that collected sojourn times
     *
     * @return A statistic for sojourn times or null
     */
    val sojournTimeStatistic: Statistic?

    /**
     * Gets the total time spent in the state
     *
     * @return a double representing the total sojourn time
     */
    val totalTimeInState: Double

    /**
     * @return returns timeStateExited - timeStateEntered
     */
    val timeInState: Double
        get() = timeStateExited - timeStateEntered
}