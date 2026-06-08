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

package ksl.modeling.agent

import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.RectangularGridSpatialModel2D
import ksl.modeling.spatial.SpatialModel

/**
 *  Extract 2D Cartesian coordinates from any [LocationIfc] that has
 *  them. Returns null for locations whose underlying spatial model
 *  does not have a 2D Cartesian representation
 *  (`DistancesModel.Location` — just a name;
 *  `GreatCircleBasedSpatialModel.GPSCoordinate` — spherical
 *  geometry, not directly Cartesian).
 *
 *  Use case: an entity is at a `LocationIfc` and code wants to query
 *  an agent-layer projection's neighbors at the same point —
 *  `space.within(loc.toPoint2D()!!, radius)`.
 */
fun LocationIfc.toPoint2D(): Point2D? = when (this) {
    is Euclidean2DPlane.Point -> Point2D(x, y)
    is RectangularGridSpatialModel2D.GridPoint -> Point2D(x, y)
    is ProjectionSpatialModel.ProjectedLocation -> point
    else -> null
}

/**
 *  Adapter that exposes an agent-layer [ContinuousProjection] as a
 *  spatial-layer [SpatialModel]. This is the bridge that lets
 *  entity-layer code requiring a `LocationIfc` (notably
 *  `MovableResource` and the `KSLProcess.move()` family) operate in
 *  the same coordinate space as agent-layer code.
 *
 *  Usage:
 *  ```
 *  val space = ContinuousProjection<MyAgent>(context, 0.0..100.0, 0.0..100.0)
 *  val sm = space.asSpatialModel()
 *  val forklift = MovableResource(parent, sm.location(50.0, 50.0), ...)
 *  ```
 *
 *  Behavior:
 *   - Distance uses the projection's own metric (Euclidean, with
 *     torus wrap if enabled).
 *   - [compareLocations] uses [Point2D] structural equality.
 *   - Locations created via [location] carry agent-layer [Point2D]
 *     values, retrievable via [LocationIfc.toPoint2D].
 *
 *  Scope of the bridge (deliberate limitations):
 *   - **One-way reuse of geometry**, not full integration. The
 *     bridge lets entity-layer types share coordinates with the
 *     projection, but moving a `MovableResource` does *not*
 *     automatically update the projection. Agent positions
 *     (`projection.moveTo(agent, ...)`) and movable-resource
 *     positions (`movableResource.currentLocation = ...`) are
 *     independent even when they share coordinates.
 *   - Each call to [ContinuousProjection.asSpatialModel] produces
 *     a *new* `ProjectionSpatialModel`. Locations created in one
 *     are not valid for another. Call once and store the result.
 *
 *  Deeper integration — including a `MovableResource` whose
 *  position is the projection's source of truth — is a separate
 *  future track (Phase 4.4 in the design doc).
 */
class ProjectionSpatialModel(
    val projection: ContinuousProjection<*>,
) : SpatialModel() {

    init {
        defaultVelocity = ksl.utilities.random.rvariable.ConstantRV.ONE
    }

    override var defaultLocation: LocationIfc = ProjectedLocation(Point2D.ORIGIN, "defaultLocation")

    /** Create a location at the given coordinates. */
    fun location(x: Double, y: Double, name: String? = null): ProjectedLocation =
        ProjectedLocation(Point2D(x, y), name)

    /** Create a location at the given [Point2D]. */
    fun location(point: Point2D, name: String? = null): ProjectedLocation =
        ProjectedLocation(point, name)

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        require(isValid(fromLocation)) {
            "The location ${fromLocation.name} is not a valid location for spatial model ${this.name}"
        }
        require(isValid(toLocation)) {
            "The location ${toLocation.name} is not a valid location for spatial model ${this.name}"
        }
        val f = fromLocation as ProjectedLocation
        val t = toLocation as ProjectedLocation
        return projection.distance(f.point, t.point)
    }

    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        require(isValid(firstLocation)) {
            "The location ${firstLocation.name} is not a valid location for spatial model ${this.name}"
        }
        require(isValid(secondLocation)) {
            "The location ${secondLocation.name} is not a valid location for spatial model ${this.name}"
        }
        val f = firstLocation as ProjectedLocation
        val t = secondLocation as ProjectedLocation
        // Structural equality on Point2D (data class) is exact; users
        // who want tolerance-based comparison should compare via
        // distance() instead.
        return f.point == t.point
    }

    /**
     *  A [LocationIfc] backed by an agent-layer [Point2D]. Created
     *  via [ProjectionSpatialModel.location]; users do not
     *  instantiate directly.
     */
    inner class ProjectedLocation internal constructor(
        val point: Point2D,
        aName: String? = null,
    ) : AbstractLocation(aName) {
        override val spatialModel: SpatialModel = this@ProjectionSpatialModel

        override fun toString(): String =
            "ProjectedLocation(point=$point, id=$id, name='$name', spatial model=${spatialModel.name})"
    }
}

/**
 *  Construct a [ProjectionSpatialModel] over this projection. Each
 *  call creates a *new* spatial model; locations created by one
 *  spatial model are not valid in another. Store the result and
 *  reuse it across all `MovableResource` / move / transport calls
 *  that need to share coordinates with this projection.
 */
fun ContinuousProjection<*>.asSpatialModel(): ProjectionSpatialModel =
    ProjectionSpatialModel(this)
