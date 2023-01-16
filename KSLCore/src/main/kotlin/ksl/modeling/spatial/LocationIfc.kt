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

interface LocationIfc {
    val id: Int
    val name: String
    val model: SpatialModel

    /**
     * Computes the distance between the current location and [location] based on
     * the spatial model's distance metric
     * @return the distance between the two locations
     */
    fun distanceTo(location: LocationIfc): Double{
        require(model.isValid(location)){"The location ${location.name} is not valid for spatial model ${model.name}"}
        return model.distance(this, location)
    }

    /**
     * Returns true if [location] is the same as this location
     * within the underlying spatial model. This is not object reference
     * equality, but rather whether the locations within the underlying
     * spatial model can be considered spatially (equivalent) according to the model.
     *
     * Requirement: The locations must be valid within the spatial model. If
     * they are not valid within same spatial model, then this method
     * returns false.
     */
    fun isLocationEqualTo(location: LocationIfc) : Boolean {
        if (!model.isValid(location)){
            return false;
        }
        return model.compareLocations(this, location)
    }

}