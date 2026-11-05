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
package ksl.calendar

import ksl.simulation.KSLEvent

/**
 * The interface defines behavior for holding, adding and retrieving events. The executive uses one
 * calendar, supplied to the model, to find the next event to run.
 *
 * Canceling is lazy, in every shipped calendar. Setting `KSLEvent.cancel` to true only marks the event,
 * and the event stays in the calendar until its time comes, when the executive drops it. This avoids
 * rebuilding the underlying structure, and it costs nothing unless a model cancels very many events
 * far in the future, which then stay in memory. A model that does so can supply a calendar that purges
 * them.
 *
 * A calendar must:
 *  - be used by one model only, since it holds that model's events;
 *  - return events in their natural order, by time, then priority, then order of scheduling;
 *  - keep returning canceled events, or remove them itself, since it isn't told about a cancellation
 *    and has to read `KSLEvent.cancel`;
 *  - remove everything in clear(), which the executive calls to reset the calendar.
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
     * The executive doesn't call this function, and setting `KSLEvent.cancel` directly doesn't
     * either, so overriding it won't tell a calendar about cancellations.
     *
     * @param event The JSLEvent to be canceled
     */
    fun cancel(event: KSLEvent<*>){
        event.cancel = true;
    }

    /**
     * Returns the number of events in the calendar
     *
     * @return An int representing the number of events.
     */
    fun size(): Int
}