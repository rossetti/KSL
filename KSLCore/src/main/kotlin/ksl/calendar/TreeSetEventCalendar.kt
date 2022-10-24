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

/** This class provides an event calendar by using a tree set to hold the underlying events.
 */
class TreeSetEventCalendar : CalendarIfc {
    private val myEventSet: SortedSet<KSLEvent<*>> = TreeSet()

    override fun add(event: KSLEvent<*>) {
        myEventSet.add(event)
    }

    override fun nextEvent(): KSLEvent<*>? {
        return if (!isEmpty) {
            val e = myEventSet.first() as KSLEvent<*>
            myEventSet.remove(e)
            e
        } else null
    }

    override fun peekNext(): KSLEvent<*>? {
        return if (!isEmpty) myEventSet.first() as KSLEvent<*> else null
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