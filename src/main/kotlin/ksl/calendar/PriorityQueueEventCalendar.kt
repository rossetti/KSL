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

/**
 * This class provides an event calendar by using a priority queue to hold the
 * underlying events.
 */
class PriorityQueueEventCalendar : CalendarIfc {
    private val myEventSet: PriorityQueue<JSLEvent<*>> = PriorityQueue()

    override fun add(event: JSLEvent<*>) {
        myEventSet.add(event)
    }

    override fun nextEvent(): JSLEvent<*> {
        return myEventSet.poll() as JSLEvent<*>
    }

    override fun peekNext(): JSLEvent<*> {
        return myEventSet.peek()
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
}