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
package ksl.calendar

import jsl.simulation.JSLEvent
import java.util.*

/** LinkedListEventCalendar is a concrete implementation of the CalendarIfc for use with the Scheduler
 * This class provides an event calendar by using a java.util.LinkedList to hold the underlying events.
 *
 */
class LinkedListEventCalendar : CalendarIfc {
    private val myEventSet: MutableList<JSLEvent<*>> = LinkedList()

    override fun add(event: JSLEvent<*>) {

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
        val i: ListIterator<JSLEvent<*>> = myEventSet.listIterator()
        while (i.hasNext()) {
            if (event.compareTo(i.next()) < 0) {
                // next() move the iterator forward, if it is < what was returned by next(), then it
                // must be inserted at the previous index
                myEventSet.add(i.previousIndex(), event)
                return
            }
        }
    }

    override fun nextEvent(): JSLEvent<*>? {
        return if (!isEmpty) myEventSet.removeAt(0) else null
    }

    override fun peekNext(): JSLEvent<*>? {
        return if (!isEmpty) myEventSet[0] else null
    }

    override val isEmpty: Boolean
        get() = myEventSet.isEmpty()

    override fun clear() {
        myEventSet.clear()
    }

    override fun cancel(event: JSLEvent<*>) {
        event.canceledFlag = true
    }

    override fun size(): Int {
        return myEventSet.size
    }

    override fun toString(): String {
        return myEventSet.toString()
    }
}