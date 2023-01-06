package ksl.modeling.spatial

interface LocationIfc {
    val id: Int
    var name: String
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