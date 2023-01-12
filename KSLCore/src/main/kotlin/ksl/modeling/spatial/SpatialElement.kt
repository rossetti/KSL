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

package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObservableIfc

interface SpatialElementIfc : ObservableIfc<SpatialElementIfc>{
    val spatialModel: SpatialModel
    val isTracked: Boolean
    val id: Int
    val name: String
    val status: SpatialModel.Status
    val initialLocation: LocationIfc
    val currentLocation: LocationIfc
    val previousLocation: LocationIfc
    val modelElement: ModelElement
    val observableComponent: ObservableComponent<SpatialElementIfc>

    fun distanceTo(location: LocationIfc): Double {
        return currentLocation.distanceTo(location)
    }

    fun distanceTo(element: SpatialElementIfc): Double {
        return currentLocation.distanceTo(element.currentLocation)
    }

    fun isLocationEqualTo(location: LocationIfc): Boolean {
        return currentLocation.isLocationEqualTo(location)
    }

    fun isLocationEqualTo(element: SpatialElementIfc): Boolean {
        return currentLocation.isLocationEqualTo(element.currentLocation)
    }

    fun initialize()
}

/**
 * Creates a spatial element associated with the spatial model at the location.
 * A spatial element is something associated with a spatial model that has a location.
 *
 */
class SpatialElement(
    override val modelElement: ModelElement,
    location: LocationIfc,
    aName: String? = null,
    override val observableComponent: ObservableComponent<SpatialElementIfc> = ObservableComponent()
) : ObservableIfc<SpatialElementIfc> by observableComponent, SpatialElementIfc {
    override val spatialModel : SpatialModel = modelElement.spatialModel
    override val id = ++spatialModel.countElements
    override val name = aName ?: ("ID_$id")
    override var status = SpatialModel.Status.NONE
    override var isTracked: Boolean = false
        internal set

    override var initialLocation = location
        set(location) {
            require(spatialModel.isValid(location)) { "The location ${location.name} is not valid for spatial model ${spatialModel.name}" }
            field = location
        }

    override var currentLocation: LocationIfc = initialLocation
        set(nextLocation) {
            require(spatialModel.isValid(nextLocation)) { "The location ${nextLocation.name} is not valid for spatial model ${spatialModel.name}" }
            previousLocation = field
            field = nextLocation
            status = SpatialModel.Status.UPDATED_LOCATION
            spatialModel.updatedElement(this)
            observableComponent.notifyAttached(this)
        }

    override var previousLocation: LocationIfc = initialLocation
        private set

    override fun initialize() {
        if (currentLocation != initialLocation){
            previousLocation = initialLocation
            currentLocation = initialLocation
        }
    }
}