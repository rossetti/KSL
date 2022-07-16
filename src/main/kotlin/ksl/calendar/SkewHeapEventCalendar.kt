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

/** This class provides an event calendar by using a skew heap to hold the underlying events.
 */
class SkewHeapEventCalendar: CalendarIfc {
    private var myRoot: BinaryNode? = null
    private var myNumEvents = 0

    override fun add(event: JSLEvent<*>) {
        myRoot = merge(myRoot, BinaryNode(event))
        myNumEvents++
    }

    override fun nextEvent(): JSLEvent<*>? {
        return if (!isEmpty) {
            val e = myRoot!!.value as JSLEvent<*>?
            myRoot = merge(myRoot!!.leftChild, myRoot!!.rightChild)
            myNumEvents--
            e
        } else null
    }

    override fun peekNext(): JSLEvent<*>? {
        return if (!isEmpty) {
            val e = myRoot!!.value as JSLEvent<*>?
            e
        } else null
    }

    override val isEmpty: Boolean
        get() = myRoot == null

    override fun clear() {
        while (nextEvent() != null) {
        }
    }

    override fun cancel(event: JSLEvent<*>) {
        event.canceledFlag = true
    }

    override fun size(): Int {
        return myNumEvents
    }

    override fun toString(): String {
        return "Number of events = $myNumEvents"
    }

    private fun merge(left: BinaryNode?, right: BinaryNode?): BinaryNode? {
        if (left == null) return right
        if (right == null) return left
        val leftValue = left.value as JSLEvent<*>?
        val rightValue = right.value as JSLEvent<*>?
        return if (leftValue!!.compareTo(rightValue) < 0) {
            val swap = left.leftChild
            left.leftChild = merge(left.rightChild, right)
            left.rightChild = swap
            left
        } else {
            val swap = right.rightChild
            right.rightChild = merge(right.leftChild, left)
            right.leftChild = swap
            right
        }
    }

    private inner class BinaryNode(var value: Any? = null) {
        /**
         * left child of node
         */
        var leftChild: BinaryNode? = null

        /**
         * right child of node
         */
        var rightChild: BinaryNode? = null

        /** return true if we are not a sentinel node
         * @return true if not a sentinal node
         */
        fun isEmpty(): Boolean {
            return false
        }
    }
}