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

/** This class provides an event calendar by using a skew heap to hold the underlying events.
 */
class SkewHeapEventCalendar: CalendarIfc {
    private var myRoot: BinaryNode? = null
    private var myNumEvents = 0

    override fun add(event: KSLEvent<*>) {
        myRoot = merge(myRoot, BinaryNode(event))
        myNumEvents++
    }

    override fun nextEvent(): KSLEvent<*>? {
        return if (!isEmpty) {
            val e = myRoot!!.value as KSLEvent<*>?
            myRoot = merge(myRoot!!.leftChild, myRoot!!.rightChild)
            myNumEvents--
            e
        } else null
    }

    override fun peekNext(): KSLEvent<*>? {
        return if (!isEmpty) {
            val e = myRoot!!.value as KSLEvent<*>?
            e
        } else null
    }

    override val isEmpty: Boolean
        get() = myRoot == null

    override fun clear() {
        while (nextEvent() != null) {
        }
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
        val leftValue = left.value as KSLEvent<*>
        val rightValue = right.value as KSLEvent<*>
        return if (leftValue.compareTo(rightValue) < 0) {
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