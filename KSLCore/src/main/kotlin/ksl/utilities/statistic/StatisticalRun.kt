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
package ksl.utilities.statistic

/** A statistical run is a sequence of objects that are determined equal based on
 * a comparator.  A single item is a run of length 1.  A set of items that are all the
 * same are considered a single run. The set (0, 1, 1, 1, 0) has 3 runs.
 *
 * @param <T> the type of object associated with the statistical run
</T> */
class StatisticalRun<T>(theStartingIndex: Int, theEndingIndex: Int, theStartingObj: T, theEndingObj: T) {

    init {
        require(theStartingIndex >= 0) { "The run's starting index must be >= 0" }
        require(theEndingIndex >= theStartingIndex) { "The run's ending index must be >= the starting index" }
    }

    /**
     *
     * @return the starting index of the run
     */
    val startingIndex: Int = theStartingIndex

    /**
     *
     * @return the ending index of the run
     */
    val endingIndex: Int = theEndingIndex

    /**
     *
     * @return the object associated with the starting index
     */
    val startingObject: T = theStartingObj

    /**
     *
     * @return the object associated with the ending index
     */
    val endingObject: T = theEndingObj

    /**
     *
     * @return the length of the run, the number of consecutive items in the run
     */
    val length: Int
        get() = endingIndex - startingIndex + 1

    override fun toString(): String {
        val sb = StringBuilder("StatisticalRun{")
        sb.appendLine()
        sb.append("length = ").append(length)
        sb.appendLine()
        sb.append("starting Index = ").append(startingIndex)
        sb.appendLine()
        sb.append("ending Index = ").append(endingIndex)
        sb.appendLine()
        sb.append("starting Object = ").append(startingObject)
        sb.appendLine()
        sb.append("ending Object = ").append(endingObject)
        sb.appendLine()
        sb.append("}")
        return sb.toString()
    }

    companion object {
        //TODO capture the run as an array of the objects
        /**
         *
         * @param list A list holding a sequence of objects for comparison, must not be null
         * @param comparator a comparator to check for equality of the objects
         * @param <T> the type of objects being compared
         * @return the List of the runs in the order that they were determined.
        </T> */
        fun <T> findRuns(list: List<T>, comparator: Comparator<T>): List<StatisticalRun<T>> {
            val listOfRuns: MutableList<StatisticalRun<T>> = ArrayList()
            if (list.isEmpty()) {
                return listOfRuns
            }
            // list has at least 1
            // list has 1 element
            if (list.size == 1) {
                val run = StatisticalRun(0, 0, list[0], list[0])
                listOfRuns.add(run)
                return listOfRuns
            }
            // list has at least 2 elements
            // starts at the same place
            var startIndex = 0
            var endIndex = startIndex
            var startingObj = list[startIndex]
            var endingObj = startingObj
            var started = true
            var ended = false
            for (i in 1 until list.size) {
                if (comparator.compare(startingObj, list[i]) == 0) {
                    // they are the same, this continues a run, with same start index and same starting object
                    endIndex = i
                    endingObj = list[i]
                    started = true
                    ended = false
                } else {
                    // they are different, this ends a run
                    val run = StatisticalRun(startIndex, endIndex, startingObj, endingObj)
                    listOfRuns.add(run)
                    startIndex = i
                    startingObj = list[i]
                    endIndex = startIndex
                    endingObj = startingObj
                    started = false
                    ended = true
                }
            }
            if (started && !ended) {
                // a run has been started that hasn't ended, close it off
                val run = StatisticalRun(startIndex, endIndex, startingObj, endingObj)
                listOfRuns.add(run)
            }
            if (startIndex == endIndex) {
                val run = StatisticalRun(startIndex, endIndex, startingObj, endingObj)
                listOfRuns.add(run)
            }
            return listOfRuns
        }


    }


}

fun main() {

//    val data = intArrayOf(0, 0, 1, 1, 0)
    val data = intArrayOf(1, 1, 1, 1, 1)
//    val data = intArrayOf(0, 0, 1, 1, 1)
//    val data = intArrayOf(1, 0, 1, 1, 1, 0)
//    val data = intArrayOf(1)
//    val data = intArrayOf(1, 0)
//    val data = intArrayOf(1, 1)
    val runs = StatisticalRun.findRuns(data.asList(), Comparator.naturalOrder())
    runs.forEach{ println(it)}
}