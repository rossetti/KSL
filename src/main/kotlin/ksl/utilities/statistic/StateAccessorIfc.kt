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