package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.observers.Observable

private var countSpatialModel: Int = 0

abstract class SpatialModel(val modelElement: ModelElement) : Observable<SpatialElement>() {
    enum class Status {
        NONE, ADDED_ELEMENT, REMOVED_ELEMENT, UPDATED_LOCATION
    }

    var status: Status = Status.NONE
        protected set
    val id: Int = ++countSpatialModel
    var name: String = "ID_$id"
    protected val myElements: MutableList<SpatialElement> = mutableListOf()
    val elements: List<SpatialElement>
        get() = myElements

    /**
     *  Subclasses should update this property to reflect the most recent
     *  spatial element that notified the spatial model of a change. Subclasses
     * are responsible for setting this within the updateLocation() method This
     * method can be used by observers to ask the SpatialModel for the element
     * that updated its location.
     */
    var updatingElement: SpatialElement? = null
        set(value) {
            field = value
            status = Status.UPDATED_LOCATION
            notifyObservers(field!!)
        }

    /**
     * Checks if the spatial model contains the supplied [element]. True indicates
     * that the element is within the spatial model. If the element has already
     * been added to this spatial model then this method should return true
     *
     */
    fun contains(element: SpatialElement) : Boolean{
        return myElements.contains(element)
    }

    protected fun addSpatialElement(element: SpatialElement){
        if (myElements.contains(element)){
            return
        }
        //TODO how is element's currentModel set?
        myElements.add(element)
        status = Status.ADDED_ELEMENT
        notifyObservers(element)
    }

    protected fun removeSpatialElement(element: SpatialElement):Boolean{
        val found = myElements.remove(element)
        if (found){
            //TODO how is element's currentModel unset?
            status = Status.REMOVED_ELEMENT
            notifyObservers(element)
        }
        return found
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
    fun isValid(element: SpatialElement) : Boolean {
        return isValid(element.location)
    }

    /**
     * Computes the distance between [firstElement] and [secondElement] based on
     * the spatial model's distance metric
     * @return the distance between the two elements
     */
    fun distance(firstElement: SpatialElement, secondElement: SpatialElement): Double{
        return distance(firstElement.location, secondElement.location)
    }

    /**
     * Returns true if [firstElement] is the same as [firstElement]
     * within the underlying spatial model. This is not object reference
     * equality, but rather whether the elements within the underlying
     * spatial model can be considered spatially (equivalent) according to the model.
     *
     * Requirement: The elements must be valid within the spatial model. If
     * they are not valid within same spatial model, then this method should
     * return false.
     */
    fun compareLocations(firstElement: SpatialElement, secondElement: SpatialElement): Boolean{
        return compareLocations(firstElement.location, secondElement.location)
    }

    /**
     * Computes the distance between [firstLocation] and [secondLocation] based on
     * the spatial model's distance metric
     * @return the distance between the two locations
     */
    abstract fun distance(firstLocation: LocationIfc, secondLocation: LocationIfc): Double

    /**
     * Returns true if [firstLocation] is the same as [secondLocation]
     * within the underlying spatial model. This is not object reference
     * equality, but rather whether the locations within the underlying
     * spatial model can be considered spatially (equivalent) according to the model.
     *
     * Requirement: The locations must be valid within the spatial model. If
     * they are not valid within same spatial model, then this method should
     * return false.
     */
    abstract fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean

    /**
     * Returns a default location within the spatial model. This could be useful for initializing
     * the location of spatial elements within the model.
     */
    abstract fun defaultLocation() : LocationIfc

}