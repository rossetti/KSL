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

package ksl.observers

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL

class AcrossReplicationHalfWidthChecker(response: ResponseCIfc, desiredHalfWidth: Double = 1.0, name: String? = null) :
    ModelElementObserver(name) {
    init {
        require(desiredHalfWidth > 0.0) {"The desired half-width must be > 0"}
    }

    private val myResponse = response as Response

    init {
        myResponse.attachModelElementObserver(this)
    }

    var confidenceLevel = DEFAULT_CONFIDENCE_LEVEL
        set(value) {
            require((0.0 < value) && (value < 1.0)){"The confidence level must be within (0, 1)"}
            field = value
        }

    var desiredHalfWidth : Double = desiredHalfWidth
        set(value) {
            require(value > 0.0) {"The desired half-width must be > 0"}
            field = value
        }

    override fun afterReplication(modelElement: ModelElement) {
        if (modelElement.model.currentReplicationNumber <= 2){
            return
        }
        val statistic = myResponse.myAcrossReplicationStatistic
        val hw = statistic.halfWidth(confidenceLevel)
        if (hw <= desiredHalfWidth){
            modelElement.model.endSimulation("Half-width = ($desiredHalfWidth) condition met for response ${myResponse.name}")
        }
    }
}