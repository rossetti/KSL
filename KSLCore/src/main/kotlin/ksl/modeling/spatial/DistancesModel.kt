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

import com.google.common.collect.BiMap
import com.google.common.collect.HashBasedTable
import com.google.common.collect.HashBiMap
import com.google.common.collect.Table
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.KSLArrays

data class DistanceData(val fromLoc: IdentityIfc, val toLoc: IdentityIfc, val distance: Double) {

    constructor(fromLocName: String, toLocName: String, distance: Double):
            this(Identity(fromLocName), Identity(toLocName), distance)

    init {
        require(distance >= 0.0) { "The distance must be >= 0.0" }
    }
}

class DistancesModel() : SpatialModel() {

    /**
     * The default distance from a location to itself, must be greater than or equal to 0.0
     */
    var defaultSameLocationDistance = 0.0
        set(value) {
            require(value >= 0.0) { "The default distance to/from same location must be >= 0.0" }
            field = value
        }
    private val distances: Table<LocationIfc, LocationIfc, Double> = HashBasedTable.create()
    private val myLocations: BiMap<String, LocationIfc> = HashBiMap.create()

    override var defaultLocation: LocationIfc = Location(Identity("defaultLocation"))

    /**
     * The locations that have been added to the model.
     */
    val locations: Set<LocationIfc>
        get() = myLocations.values

    /**
     *  The names of the locations.
     */
    val locationNames: Set<String>
        get() = myLocations.keys

    /**
     *  Adds the [distance] from location [fromLoc] to location [toLoc] where [fromLoc] and
     *  [toLoc] are string names of locations.  If a location with the name does not already
     *  exist in the model, then a new location with the name is created. The flag [symmetric] will cause
     *  an additional distance to be added going from [toLoc] to location [fromLoc] that has
     *  the same distance. The default value of the flag is false. The pair must not have already been added to the model.
     *  @return the pair of locations added
     */
    fun addDistance(
        fromLoc: IdentityIfc,
        toLoc: IdentityIfc,
        distance: Double,
        symmetric: Boolean = false
    ): Pair<LocationIfc, LocationIfc> {
        require(distance >= 0.0) { "The distance must be >= 0.0" }
        val f = if (containsName(fromLoc.name)) {
            myLocations[fromLoc.name]!!
        } else {
            Location(fromLoc)
        }
        val t = if (containsName(toLoc.name)) {
            myLocations[toLoc.name]!!
        } else {
            Location(toLoc)
        }
        addDistance(f, t, distance, symmetric)
        return Pair(f, t)
    }

    /**
     *  Adds a list of [distanceData] to the model.
     *  @return a map containing the pairs of locations created by the names
     *  of the distances in the list
     */
    fun addDistances(distanceData: List<DistanceData>): Map<LocationIfc, LocationIfc> {
        val map = mutableMapOf<LocationIfc, LocationIfc>()
        for (d in distanceData) {
            val (f, t) = addDistance(d.fromLoc, d.toLoc, d.distance)
            map[f] = t
        }
        return map
    }

    /**
     * Assumes that [matrix] is square and contains the from-to distances. Any values on
     * the diagonal are ignored. No values can be 0.0
     *  @return a map containing the pairs of locations created. Each location is named Loc_k, where
     *  k is the index of the location in the array
     */
    fun addDistances(matrix: Array<DoubleArray>): Map<LocationIfc, LocationIfc> {
        require(KSLArrays.isSquare(matrix)){"The supplied distance matrix was not square"}
        val map = mutableMapOf<LocationIfc, LocationIfc>()
        for(i in matrix.indices){
            for (j in matrix[i].indices){
                if (i != j){
                    val (f, t) = addDistance(Identity("Loc_$i"), Identity("Loc_$j"), matrix[i][j])
                    map[f] = t
                }
            }
        }
        return map
    }

    /**
     *  @return true if the [name] of the location has been previously added as part of a distance.
     */
    fun containsName(name: String): Boolean {
        return myLocations.containsKey(name)
    }

    /**
     *  @return the location associated with the supplied [name]. Null if the name is not
     *  associated with a location in the model
     */
    fun location(name: String): LocationIfc? {
        return myLocations[name]
    }

    /**
     * Adds a [distance] value between the pair of locations, going from [fromLocation] to [toLocation]. The distance
     * must be greater than or equal to 0.0. The flag [symmetric] will cause an additional distance to be added going
     * from [toLocation] to location [fromLocation] that has the same distance. The default value of the flag is false.
     * The pair must not have already been added to the model. Use changeDistance() in that case.
     */
    fun addDistance(fromLocation: LocationIfc, toLocation: LocationIfc, distance: Double, symmetric: Boolean = false) {
        require(distance >= 0.0) { "The distance must be >= 0.0" }
        require(
            !distances.contains(
                fromLocation,
                toLocation
            )
        ) { "The pair (${fromLocation.name}, ${toLocation.name}) is already in the model, use changeDistance() instead." }
        distances.put(fromLocation, toLocation, distance)
        if (symmetric) {
            distances.put(toLocation, fromLocation, distance)
        }
        myLocations[fromLocation.name] = fromLocation
        myLocations[toLocation.name] = toLocation
    }

    /**
     * Changes a [distance] value between the pair of locations, going from [fromLocation] to [toLocation]. The distance
     * must be greater than or equal to 0.0. The pair must already be part of the model.
     */
    fun changeDistance(fromLocation: LocationIfc, toLocation: LocationIfc, distance: Double) {
        require(distance >= 0.0) { "The distance must be >= 0.0" }
        require(isValid(fromLocation)) { "The location ${fromLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(toLocation)) { "The location ${toLocation.name} is not a valid location for spatial model ${this.name}" }
        require(
            distances.contains(
                fromLocation,
                toLocation
            )
        ) { "The pair (${fromLocation.name}, ${toLocation.name}) is not in the model" }
        distances.put(fromLocation, toLocation, distance)
    }

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        require(isValid(fromLocation)) { "The location ${fromLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(toLocation)) { "The location ${toLocation.name} is not a valid location for spatial model ${this.name}" }
        if (fromLocation == toLocation) {
            // assume user did not add same location pair, but requested the distance
            if (!distances.contains(fromLocation, toLocation)) {
                // we return the default if the pair hasn't been added
                return defaultSameLocationDistance
            }
        }
        require(
            distances.contains(
                fromLocation,
                toLocation
            )
        ) { "The pair (${fromLocation.name}, ${toLocation.name}) is not in the model" }
        // the locations are the same and a distance is recorded, or
        // the locations are different and a distance is recorded, so just return it
        return distances.get(fromLocation, toLocation)!!
    }

    /**
     * For this model, this returns true if and only if the locations are the same object instances.
     */
    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        require(isValid(firstLocation)) { "The location ${firstLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(secondLocation)) { "The location ${secondLocation.name} is not a valid location for spatial model ${this.name}" }
        return firstLocation == secondLocation
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Distances")
        for(x in distances.cellSet()){
            sb.appendLine("From: ${x.rowKey.name}  ---> To: ${x.columnKey.name}  d = ${x.value}")
        }
        return sb.toString()
    }

    /** Represents a location within this spatial model.
     *
     * @param identity the identity of the location
     */
    inner class Location(identity: IdentityIfc) : AbstractLocation(identity) {
        init {
            require(!myLocations.containsKey(identity.name)) { "The location identity ${identity.name} already exists in the model" }
        }

        constructor(name: String? = null): this(Identity(name))

        override val spatialModel: SpatialModel = this@DistancesModel
        override fun toString(): String {
            return "Location(id=$id, name='$name', spatial model=${spatialModel.name})"
        }

    }

}