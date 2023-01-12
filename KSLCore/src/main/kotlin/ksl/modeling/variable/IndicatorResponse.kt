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

package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.toDouble

class IndicatorResponse(predicate: (Double) -> Boolean,
                        observedResponse: Response, name: String? = null) : Response(observedResponse, name) {

    private val myObserver = ResponseObserver()
    private val myPredicate = predicate
    private val myObservedResponse = observedResponse
    init {
        myObservedResponse.attachModelElementObserver(myObserver)
    }

    fun detach(){
        myObservedResponse.detachModelElementObserver(myObserver)
    }

    private inner class ResponseObserver: ModelElementObserver(){
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val response = modelElement as Response
            value = myPredicate.invoke(response.value).toDouble()
        }
    }
}
