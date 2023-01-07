package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.math.KSLMath
import kotlin.math.sqrt

class Euclidean2DPlane(modelElement: ModelElement) : SpatialModel(modelElement) {
    private var locationCount = 0

    var defaultLocationPrecision = KSLMath.defaultNumericalPrecision
        set(precision) {
            require(precision > 0.0) { "The precision must be > 0.0." }
            field = precision
        }

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        require(isValid(fromLocation)) { "The location ${fromLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(toLocation)) { "The location ${toLocation.name} is not a valid location for spatial model ${this.name}" }
        val f = fromLocation as Location
        val t = toLocation as Location
        val dx = f.x - t.x
        val dy = f.y - t.y
        return sqrt(dx * dx + dy * dy)
    }

    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        require(isValid(firstLocation)) { "The location ${firstLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(secondLocation)) { "The location ${secondLocation.name} is not a valid location for spatial model ${this.name}" }
        val f = firstLocation as Location
        val t = secondLocation as Location
        val b1 = KSLMath.equal(f.x, t.x, defaultLocationPrecision)
        val b2 = KSLMath.equal(f.y, t.y, defaultLocationPrecision)
        return b1 && b2
    }

    /** Represents a location within this spatial model.
     *
     * @param aName the name of the location, will be assigned based on ID_id if null
     */
    inner class Location(val x: Double, val y: Double, aName: String? = null) : LocationIfc {
        override val id: Int = ++locationCount
        override val name: String = aName ?: "ID_$id"
        override val model: SpatialModel = this@Euclidean2DPlane
        override fun toString(): String {
            return "Location(x=$x, y=$y, id=$id, name='$name', spatial model=${model.name})"
        }


    }
}