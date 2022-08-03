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
    var priority = 0
    var id = 0
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