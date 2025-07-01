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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.simulation

/**
 *
 * @author rossetti
 */
abstract class ConditionalAction : Comparable<ConditionalAction> {
    var priority : Int = 0
    var id : Int = 0
    abstract fun testCondition(): Boolean
    abstract fun action()

    /** Returns a negative integer, zero, or a positive integer
     * if this object is less than, equal to, or greater than the
     * specified object.
     *
     * Natural ordering: time, then priority, then order of creation
     *
     * Lower time, lower priority, lower order of creation goes first
     *
     * Throws ClassCastException if the specified object's type
     * prevents it from begin compared to this object.
     *
     * Throws RuntimeException if the id's of the objects are the same,
     * but the references are not when compared with equals.
     *
     * Note:  This class may have a natural ordering that is inconsistent
     * with equals.
     * @param other The action to compare
     * @return Returns a negative integer, zero, or a positive integer
     * if this object is less than, equal to, or greater than the
     * specified object.
     */
    override operator fun compareTo(other: ConditionalAction): Int {

        // check priorities
        if (priority < other.priority) return -1
        if (priority > other.priority) return 1

        // time and priorities are equal, compare ids
        if (id < other.id) // lower id, implies created earlier
            return -1
        if (id > other.id) return 1

        // if the id's are equal then the object references must be equal
        // if this is not the case there is a problem
        return if (this == other) 0 else throw RuntimeException("Id's were equal, but references were not, in ConditionalAction compareTo")
    }
}