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
package ksl.calendar

import ksl.simulation.KSLEvent

/**
 * The interface defines behavior for holding, adding and retrieving JSLEvents.
 *
 *
 */
interface CalendarIfc {
    /**
     * The add method will place the provided JSLEvent into the
     * underlying data structure ensuring the ordering of the events
     * to be processed
     *
     * @param event The JSLEvent to be added to the calendar
     */
    fun add(event: KSLEvent<*>)

    /**
     * Returns the next JSLEvent to be executed. The event is removed from
     * the calendar if it exists
     *
     * @return The JSLEvent to be executed next
     */
    fun nextEvent(): KSLEvent<*>?

    /**
     * Peeks at the next event without removing it
     *
     * @return
     */
    fun peekNext(): KSLEvent<*>?

    /**
     * Checks to see if the calendar is empty
     *
     * @return true is empty, false is not empty
     */
    val isEmpty: Boolean

    /**
     * Checks to see if the calendar is not empty
     *
     * @return true is not empty, false is empty
     */
    val isNotEmpty: Boolean
        get() = !isEmpty

    /**
     * Clears or cancels every event in the data structure. Removes all
     * JSLEvents
     * from the data structure.
     */
    fun clear()

    /**
     * Cancels the supplied JSLEvent in the calendar. Canceling does not remove
     * the event from the data structure. It simply indicates that the
     * scheduled event must not be executed.
     *
     * @param event The JSLEvent to be canceled
     */
    fun cancel(event: KSLEvent<*>){
        event.cancelled = true;
    }

    /**
     * Returns the number of events in the calendar
     *
     * @return An int representing the number of events.
     */
    fun size(): Int
}