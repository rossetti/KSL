package ksl.modeling.spatial

import com.google.common.collect.BiMap
import com.google.common.collect.HashBasedTable
import com.google.common.collect.HashBiMap
import com.google.common.collect.Table
import ksl.simulation.ModelElement

data class Distance(val fromLoc: String, val toLoc: String, val distance: Double) {
    init {
        require(distance >= 0.0) { "The distance must be >= 0.0" }
    }
}

class DistancesModel(modelElement: ModelElement) : SpatialModel(modelElement) {
    private var locationCount = 0

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
     *  Adds a list of [distances] to the model.
     *  @return a map containing the pairs of locations created by the names
     *  of the distances in the list
     */
    fun addDistances(distances: List<Distance>): Map<LocationIfc, LocationIfc> {
        val map = mutableMapOf<LocationIfc, LocationIfc>()
        for (d in distances) {
            val f = Location(d.fromLoc)
            val t = Location(d.toLoc)
            addDistance(f, t, d.distance)
            map[f] = t
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
     *  @return the location associated with the supplied [name]. The name must be associated with
     *  some location in the model.
     */
    fun location(name: String): LocationIfc {
        require(myLocations.containsKey(name)) { "The name $name does not correspond to a valid location" }
        return myLocations[name]!!
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

    /** Represents a location within this spatial model.
     *
     * @param aName the name of the location, will be assigned based on ID_id if null
     */
    inner class Location(aName: String? = null) : LocationIfc {
        override val id: Int = ++locationCount
        override val name: String = aName ?: "ID_$id"
        override val model: SpatialModel = this@DistancesModel
    }

}