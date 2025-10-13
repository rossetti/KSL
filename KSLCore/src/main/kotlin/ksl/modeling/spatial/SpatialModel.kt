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

import ksl.utilities.GetValueIfc
import ksl.utilities.observers.Observable
import ksl.utilities.random.rvariable.ConstantRV

private var countSpatialModel: Int = 0

abstract class SpatialModel() : Observable<SpatialElementIfc>() {
    protected var locationCount : Int = 0
    var countElements: Int = 0
        internal set

    enum class Status {
        NONE, ADDED_ELEMENT, UPDATED_LOCATION, REMOVED_ELEMENT, TRANSFERRED_ELEMENT,  TRANSFERRED, CELL_CHANGED
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

    /**
     * The default velocity for elements that move within the spatial model
     */
    var defaultVelocity: GetValueIfc = ConstantRV.ONE

    //TODO make this abstract
    var defaultMovementController: MovementControllerIfc = DefaultMovementController()

    /**
     *  The default initial location.
     */
    abstract var defaultLocation: LocationIfc

    protected val myElements: MutableList<SpatialElement> = mutableListOf()
    val elements: List<SpatialElementIfc>
        get() = myElements

    /**
     *  Causes the element to be tracked, held, by the spatial model. The user
     *  is responsible for maintaining this list
     */
    open fun track(element: SpatialElement) {
        require(isValid(element)){"The element, ${element.spatialName} is not valid for spatial model ${this.name}"}
        if (myElements.contains(element)){
            return
        }
        element.isTracked = true
        myElements.add(element)
        status = Status.ADDED_ELEMENT
        notifyObservers(element)
    }

    open fun stopTracking(element: SpatialElement){
        if (!isValid(element)){
            return
        }
        if (myElements.remove(element)){
            element.isTracked = false
            status = Status.REMOVED_ELEMENT
            notifyObservers(element)
        }
    }

    fun stopAllTracking(){
        val list = ArrayList(myElements)
        for(e in list){
            stopTracking(e)
        }
        myElements.clear()
    }

    /**
     * Subclasses can override this method to provide specific behavior
     * when the [element] has updated it location. Observers of the spatial
     * model are notified (automatically) after this function is called.
     */
    protected open fun updatedElementLocation(element: SpatialElementIfc) {
    }

    internal fun updatedElement(element: SpatialElementIfc) {
        updatedElementLocation(element)
        status = Status.UPDATED_LOCATION
        notifyObservers(element)
    }

    /**
     * Checks if the spatial model contains the supplied [element]. True indicates
     * that the element is within the spatial model. If the element has already
     * been added to this spatial model then this method should return true
     *
     */
    fun isTracking(element: SpatialElementIfc): Boolean {
        return myElements.contains(element)
    }

//TODO once associated with a spatial model an element cannot be unassociated
// this is due to the problem of remembering the initial spatial model
// and how to return the element to its initial spatial model if a transfer occurred
// need to deal with tracking, etc.

//    /**
//     *  The [element] is transferred to the spatial model [newSpatialModel] and located
//     *  at the new location [newLocation].  The transferring element must be contained within
//     *  this spatial model.  A new element is created within the supplied spatial model
//     *  that has the same name and the same observers. The new element is returned. The observers
//     *  of this spatial model are notified that the element was transferred.  The transferring [element]
//     *  will no longer be managed by this spatial model and should not be used.
//     *
//     *  @return the new spatial element at the new location within the different spatial model.
//     */
//    open fun transferSpatialElement(
//        element: SpatialElement,
//        newSpatialModel: SpatialModel,
//        newLocation: LocationIfc
//    ): SpatialElementIfc {
//        require(contains(element)) { "The transferring element ${element.name} does not belong to the spatial model ${this.name} " }
//        require(newSpatialModel.isValid(newLocation)) { "The location ${newLocation.name} is not valid for spatial model ${newSpatialModel.name}" }
//        myElements.remove(element)
//        status = Status.TRANSFERRED_ELEMENT
//        notifyObservers(element)
//        element.status = Status.TRANSFERRED
//        return SpatialElement(newSpatialModel, newLocation, element.name, element.observableComponent)
//    }

    /**
     * @return true if [location] is associated with this spatial model
     */
    fun isValid(location: LocationIfc): Boolean {
        return this == location.spatialModel
    }

    /**
     * @return true if [element] is associated with this spatial model
     */
    fun isValid(element: SpatialElementIfc): Boolean {
        return isValid(element.currentLocation) && element.spatialModel == this
    }

    /**
     * Computes the distance between [fromElement] and [toElement] based on
     * the spatial model's distance metric
     * @return the distance between the two elements
     */
    fun distance(fromElement: SpatialElementIfc, toElement: SpatialElementIfc): Double {
        require(isValid(fromElement)) { "The element ${fromElement.spatialName} is not a valid element for the spatial model ${this.name}" }
        require(isValid(toElement)) { "The element ${fromElement.spatialName} is not a valid element for the spatial model ${this.name}" }
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
    fun compareLocations(firstElement: SpatialElementIfc, secondElement: SpatialElementIfc): Boolean {
        require(isValid(firstElement)) { "The element ${firstElement.spatialName} is not a valid element for the spatial model ${this.name}" }
        require(isValid(secondElement)) { "The element ${firstElement.spatialName} is not a valid element for the spatial model ${this.name}" }
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

    /** Represents a location within this spatial model.
     *
     * @param aName the name of the location, will be assigned based on ID_id if null
     */
    abstract inner class AbstractLocation(aName: String? = null) : LocationIfc {
        final override var label: String? = null
        final override val id: Int = ++locationCount
        override val name: String = aName ?: "ID_$id"

    }
}