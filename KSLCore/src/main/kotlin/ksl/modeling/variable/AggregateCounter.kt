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

package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import kotlin.math.abs

/** An aggregate time-weighted response observes many other time weighted response variables. Whenever any
 * variable that it observes changes, it is incremented by or decremented by the amount of the change.
 * Thus, the resulting response is the total (aggregate) of all the underlying observed responses
 * at any time.
 *
 * @param parent the parent model element
 * @param name the name of the aggregate response
 */
class AggregateCounter(parent: ModelElement, name : String? = null) : Counter(parent, name) {

    private val responses = mutableSetOf<Counter>()
    private val myObserver = CounterObserver()

    private inner class CounterObserver: ModelElementObserver(){
        override fun update(modelElement: ModelElement) {
            // must be a counter
            val response = modelElement as Counter
            val change = response.value - response.previousValue
            if (change >= 0.0) {
                increment(change)
            }
        }
    }

    /** The counter will be observed by the aggregate such that whenever the counter
     * changes, the aggregate will change
     *
     * @param counter the response to observe
     */
    fun observe(counter: CounterCIfc){
        if (counter is Counter){
            observe(counter)
        }
    }

    /** The counter will be observed by the aggregate such that whenever the counter
     * changes, the aggregate will change
     *
     * @param counter the counter to observe
     */
    fun observe(counter: Counter){
        if(!responses.contains(counter)){
            responses.add(counter)
            counter.attachModelElementObserver(myObserver)
        }
    }

    /**
     *  Causes all the counters to be observed
     */
    fun observeAll(counters: Collection<Counter>){
        for(counter in counters){
            observe(counter)
        }
    }

    /** The counter will stop being observed by the aggregate.
     *
     * @param counter the counter to stop observing
     */
    fun remove(counter: Counter){
        if (responses.contains(counter)){
            responses.remove(counter)
            counter.detachModelElementObserver(myObserver)
        }
    }

    /** The counter will stop being observed by the aggregate.
     *
     * @param counter the counter to stop observing
     */
    fun remove(counter: CounterCIfc){
        if (counter is TWResponse){
            remove(counter)
        }
    }

    /**
     * @param counters the counters to remove (stop observing)
     */
    fun removeAll(counters: Collection<Counter>){
        for(counter in counters){
            remove(counter)
        }
    }
}