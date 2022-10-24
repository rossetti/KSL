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
package ksl.calendar

import ksl.simulation.KSLEvent
import java.util.*

/** LinkedListEventCalendar is a concrete implementation of the CalendarIfc for use with the Scheduler
 * This class provides an event calendar by using a java.util.LinkedList to hold the underlying events.
 *
 */
class LinkedListEventCalendar : CalendarIfc {
    private val myEventSet: MutableList<KSLEvent<*>> = LinkedList()

    override fun add(event: KSLEvent<*>) {

        // nothing in calendar, just add it, and return
        if (myEventSet.isEmpty()) {
            myEventSet.add(event)
            return
        }

        // might as well check for worse case, if larger than the largest then put it at the end and return
        if (event.compareTo(myEventSet[myEventSet.size - 1]) >= 0) {
            myEventSet.add(event)
            return
        }

        // now iterate through the list
        val i: ListIterator<KSLEvent<*>> = myEventSet.listIterator()
        while (i.hasNext()) {
            if (event.compareTo(i.next()) < 0) {
                // next() move the iterator forward, if it is < what was returned by next(), then it
                // must be inserted at the previous index
                myEventSet.add(i.previousIndex(), event)
                return
            }
        }
    }

    override fun nextEvent(): KSLEvent<*>? {
        return if (!isEmpty) myEventSet.removeAt(0) else null
    }

    override fun peekNext(): KSLEvent<*>? {
        return if (!isEmpty) myEventSet[0] else null
    }

    override val isEmpty: Boolean
        get() = myEventSet.isEmpty()

    override fun clear() {
        myEventSet.clear()
    }

    override fun size(): Int {
        return myEventSet.size
    }

    override fun toString(): String {
        return myEventSet.toString()
    }
}