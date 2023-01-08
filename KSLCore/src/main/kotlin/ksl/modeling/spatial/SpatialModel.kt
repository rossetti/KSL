package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.makeNameFromClass
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObservableIfc

private var countSpatialModel: Int = 0

abstract class SpatialModel(val modelElement: ModelElement) : Observable<SpatialModel.SpatialElement>() {

    var countElements: Int = 0
        protected set

    enum class Status {
        NONE, ADDED_ELEMENT, TRANSFERRED_ELEMENT, UPDATED_LOCATION, TRANSFERRED
    }

    var status: Status = Status.NONE
        protected set
    val id: Int = ++countSpatialModel
    var name: String = makeName()
    private fun makeName(str: String? = null): String {
        return if (str == null) {
            // no name is being passed, construct a default name
            var s = this::class.simpleName!!
            val k = s.lastIndexOf(".")
            if (k != -1) {
                s = s.substring(k + 1)
            }
            s + "_" + id
        } else {
            str
        }
    }

    protected val myElements: MutableList<SpatialElement> = mutableListOf()
    val elements: List<SpatialElement>
        get() = myElements

    /**
     * Subclasses can override this method to provide specific behavior
     * when the [element] has updated it location. Observers of the spatial
     * model are notified (automatically) after this function is called.
     */
    protected fun updatedElementLocation(element: SpatialElement) {
        status = Status.UPDATED_LOCATION
        notifyObservers(element)
    }

    /**
     * Checks if the spatial model contains the supplied [element]. True indicates
     * that the element is within the spatial model. If the element has already
     * been added to this spatial model then this method should return true
     *
     */
    fun contains(element: SpatialElement): Boolean {
        return myElements.contains(element)
    }

    /**
     *  The [element] is transferred to the spatial model [spatialModel] and located
     *  at the new location [newLocation].  The transferring element must be contained within
     *  this spatial model.  A new element is created within the supplied spatial model
     *  that has the same name and the same observers. The new element is returned. The observers
     *  of this spatial model are notified that the element was transferred.  The transferring [element]
     *  will no longer be managed by this spatial model and should not be used.
     *
     *  @return the new spatial element at the new location within the different spatial model.
     */
    fun transferSpatialElement(
        element: SpatialElement,
        spatialModel: SpatialModel,
        newLocation: LocationIfc
    ): SpatialElement {
        require(contains(element)) { "The transferring element ${element.name} does not belong to the spatial model ${this.name} " }
        require(spatialModel.isValid(newLocation)) { "The location ${newLocation.name} is not valid for spatial model ${spatialModel.name}" }
        myElements.remove(element)
        status = Status.TRANSFERRED_ELEMENT
        notifyObservers(element)
        element.status = Status.TRANSFERRED
        return spatialModel.SpatialElement(newLocation, element.name, element.observableComponent)
    }

    /**
     * @return true if [location] is associated with this spatial model
     */
    fun isValid(location: LocationIfc): Boolean {
        return this == location.model
    }

    /**
     * @return true if [element] is associated with this spatial model
     */
    fun isValid(element: SpatialElement): Boolean {
        return isValid(element.currentLocation) && contains(element)
    }

    /**
     * Computes the distance between [fromElement] and [toElement] based on
     * the spatial model's distance metric
     * @return the distance between the two elements
     */
    fun distance(fromElement: SpatialElement, toElement: SpatialElement): Double {
        require(isValid(fromElement)) { "The element ${fromElement.name} is not a valid element for the spatial model ${this.name}" }
        require(isValid(toElement)) { "The element ${fromElement.name} is not a valid element for the spatial model ${this.name}" }
        return distance(fromElement.currentLocation, toElement.currentLocation)
    }

    /**
     * Returns true if [firstElement] is the same as [secondElement]
     * within the underlying spatial model. This is not object reference
     * equality, but rather whether the elements within the underlying
     * spatial model can be considered spatially (equivalent) according to the model.
     * This may or may not imply that the distance between the elements is zero.
     * No assumptions about distance are implied by true.
     *
     * Requirement: The elements must be valid within the spatial model.
     */
    fun compareLocations(firstElement: SpatialElement, secondElement: SpatialElement): Boolean {
        require(isValid(firstElement)) { "The element ${firstElement.name} is not a valid element for the spatial model ${this.name}" }
        require(isValid(secondElement)) { "The element ${firstElement.name} is not a valid element for the spatial model ${this.name}" }
        return compareLocations(firstElement.currentLocation, secondElement.currentLocation)
    }

    /**
     * Computes the distance between [fromLocation] and [toLocation] based on
     * the spatial model's distance metric
     * @return the distance between the two locations
     */
    abstract fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double

    /**
     * Returns true if [firstLocation] is the same as [secondLocation]
     * within the underlying spatial model. This is not object reference
     * equality, but rather whether the locations within the underlying
     * spatial model can be considered spatially (equivalent) according to the model.
     * This may or may not imply that the distance between the locations is zero.
     * No assumptions about distance are implied by true.
     *
     * Requirement: The locations must be valid within the spatial model.
     */
    abstract fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean

    /**
     * Creates a spatial element at the location within the spatial model
     *
     */
    inner class SpatialElement(
        location: LocationIfc,
        aName: String? = null,
        internal val observableComponent: ObservableComponent<SpatialElement> = ObservableComponent()
    ) : ObservableIfc<SpatialElement> by observableComponent {
        val id = ++countElements
        val name = aName ?: ("ID_$id")

        var status = Status.NONE
            internal set
        val spatialModel = this@SpatialModel

        var initialLocation = location
            set(location) {
                require(spatialModel.isValid(location)) { "The location ${location.name} is not valid for spatial model ${spatialModel.name}" }
                field = location
            }

        var currentLocation: LocationIfc = initialLocation
            set(nextLocation) {
                require(spatialModel.isValid(nextLocation)) { "The location ${nextLocation.name} is not valid for spatial model ${spatialModel.name}" }
                previousLocation = field
                field = nextLocation
                spatialModel.updatedElementLocation(this)
                status = Status.UPDATED_LOCATION
                observableComponent.notifyAttached(this)
            }

        var previousLocation: LocationIfc = initialLocation
            private set

        init {
            this@SpatialModel.myElements.add(this)
            status = Status.ADDED_ELEMENT
            notifyObservers(this)
        }

        fun distanceTo(location: LocationIfc): Double {
            return currentLocation.distanceTo(location)
        }

        fun distanceTo(element: SpatialElement): Double {
            return currentLocation.distanceTo(element.currentLocation)
        }

        fun isLocationEqualTo(location: LocationIfc): Boolean {
            return currentLocation.isLocationEqualTo(location)
        }

        fun isLocationEqualTo(element: SpatialElement): Boolean {
            return currentLocation.isLocationEqualTo(element.currentLocation)
        }

    }

}