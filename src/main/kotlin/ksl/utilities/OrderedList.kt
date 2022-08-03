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
package ksl.utilities

//import java.util.*

/** This class encapsulates a list that is ordered
 *  Unfortunately, if the element is changed after being added to the list such that
 *  the comparison changes, then the ordering of the list will not be maintained.
 *  Ideally, only immutable objects are placed in the list.
 * @author rossetti
 * @param <T> the type held in the list
</T> */
class OrderedList<T : Comparable<T>> {

//    private val myList: MutableList<T> = LinkedList()
    private val myList: MutableList<T> = mutableListOf()

    val size = myList.size

    /** The elements are added and the list is changed to maintain the order
     *
     * @param element the element to add
     */
    fun add(element: T): Boolean {
        // nothing in the list, just add to beginning
        if (myList.isEmpty()) {
            return myList.add(element)
        }

        // might as well check for worse case, if larger than the largest
        // then put it at the end and return
        if (element.compareTo(myList[myList.size - 1]) >= 0) {
            return myList.add(element)
        }

        // now iterate through the list
        val i: ListIterator<T> = myList.listIterator()
        while (i.hasNext()) {
            if (element.compareTo(i.next()) < 0) {
                // next() moved the iterator forward, if it is < what was returned by next(), then it
                // must be inserted at the previous index
                myList.add(i.previousIndex(), element)
                return true
            }
        }
        return false
    }

    /** The elements are added and the ordering implied by the elements is maintained.
     *
     * @param elements the elements to add
     */
    fun addAll(elements: Collection<T>) {
        for(element in elements){
            add(element)
        }
    }

    /**
     *  Removes all elements from the list
     */
    fun clear(){
        myList.clear()
    }

    /**
     * @return true if the element is in the list, false otherwise
     */
    fun contains(element: T): Boolean {
        return myList.contains(element)
    }

    fun isEmpty(): Boolean {
        return myList.isEmpty()
    }

    fun isNotEmpty(): Boolean {
        return myList.isNotEmpty()
    }

    operator fun iterator() : MutableIterator<T>{
        return myList.iterator()
    }

    /**
     * @return a reference to the first element in the list, or null if the list is empty
     */
    private fun peekFirst(): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            myList[0]
        }
    }

    /**
     * The list order is maintained.
     *
     * @return removes the first element in the list and returns it, or null if the list is empty
     */
    fun removeFirst(): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            val obj = myList[0]
            myList.removeAt(0)
            obj
        }
    }

    /** The list order is maintained.
     * @param elementIndex the index of the element to remove
     * @return the returned element
     */
    fun remove(elementIndex: Int): T {
        return myList.removeAt(elementIndex)
    }

    /** The list order is maintained.
     * @param element the element to remove
     * @return true if removed, false otherwise
     */
    fun remove(element: T): Boolean {
        return myList.remove(element)
    }

}