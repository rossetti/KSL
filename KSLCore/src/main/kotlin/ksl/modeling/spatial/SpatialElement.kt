package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObservableIfc

interface SpatialElementIfc : ObservableIfc<SpatialElementIfc>{
    val spatialModel: SpatialModel
    val id: Int
    val name: String
    val status: SpatialModel.Status
    val initialLocation: LocationIfc
    val currentLocation: LocationIfc
    val previousLocation: LocationIfc
    val modelElement: ModelElement?
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
}

/**
 * Creates a spatial element at the location within the spatial model
 *
 */
class SpatialElement(
    override val spatialModel : SpatialModel,
    location: LocationIfc,
    aName: String? = null,
    override val observableComponent: ObservableComponent<SpatialElementIfc> = ObservableComponent()
) : ObservableIfc<SpatialElementIfc> by observableComponent, SpatialElementIfc {
    override val id = ++spatialModel.countElements
    override val name = aName ?: ("ID_$id")
    override var status = SpatialModel.Status.NONE

    override var modelElement: ModelElement? = null

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

    init {
        spatialModel.addElementInternal(this)
    }

}