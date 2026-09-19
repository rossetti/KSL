/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

/**
 * What a fleet needs from the layout it works over: **named places, how far apart they are, and
 * whether one can be reached from another**.
 *
 * The companion of [VehicleMovementIfc], and the second of the two things that stood between a fleet
 * and a substrate. That one answers *how a vehicle gets somewhere*; this one answers *what and where
 * "somewhere" is*. Between them a dispatcher, a tour, a stop and a line can be written without
 * naming a guide path, a projection or a plane.
 *
 * **Places are named, and that is deliberate.** A modeller posts a transport from `"Machining"` to
 * `"Paint"`, declares a stop at `"Depot"`, and reads a report row that says where a vehicle went.
 * Names are what a *model* is written in; a `LocationIfc` is what a *space* is written in. This
 * interface is the join, and it is the reason the fleet layer never had to be retyped to carry
 * location objects around.
 *
 * **Distance here is between two places, not from a vehicle.** How far a *vehicle* is from
 * somewhere is [VehicleMovementIfc.pathDistanceTo], which may differ: on a one-way loop a vehicle
 * standing just past a junction must go all the way round, and no fact about the layout alone can
 * say so. A policy that scores a whole journey uses both -- this for the leg between two fixed
 * places, that for the leg the vehicle has yet to make.
 *
 * `GuidedPathNetwork` implements it directly, since its intersections and station aliases are
 * already named places. Any [SpatialModel] with [SpatialModel.namedLocations] joins through
 * [SpatialModelFleetSpace].
 */
interface FleetSpaceIfc {

    /** What the layout is called, for messages that have to name it. */
    val name: String

    /** The place this name refers to, or null when the layout has no such place. */
    fun location(name: String): LocationIfc?

    /** The place this name refers to. Raises when there is none, naming what was asked for. */
    fun requireLocation(name: String): LocationIfc

    /** How far apart two places are, by this layout's own metric. */
    fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double

    /** True when [toLocation] can be reached from [fromLocation] at all. */
    fun isReachable(fromLocation: LocationIfc, toLocation: LocationIfc): Boolean
}

/**
 * Any spatial model with named locations, seen as a layout a fleet can work over.
 *
 * **Everywhere is reachable from everywhere**, which is what a free path means and is the honest
 * answer for a plane, a grid, or a table of pairwise distances: there are no one-way aisles and no
 * places a vehicle cannot get to. A layout with genuine unreachability -- a guide path -- says so
 * for itself rather than through this.
 *
 * @param model the spatial model the places belong to and whose metric measures between them
 * @param places the named places a vehicle can be sent to. Defaults to the model's own
 *   [SpatialModel.namedLocations], which a `DistancesModel` maintains and a plane does not: a plane
 *   has no bounded set of places, so a fleet over one says which points it calls at
 */
class SpatialModelFleetSpace @JvmOverloads constructor(
    val model: SpatialModel,
    places: List<LocationIfc> = model.namedLocations
) : FleetSpaceIfc {

    private val byName: Map<String, LocationIfc> = places.associateBy { it.name }

    init {
        require(byName.isNotEmpty()) {
            "Spatial model (${model.name}) has no named locations, so a fleet has nowhere to be " +
                    "sent. Name the places a vehicle can be asked to go to before using it as a " +
                    "fleet's layout."
        }
    }

    override val name: String
        get() = model.name

    /** The places this layout knows, in the order the spatial model reports them. */
    val locationNames: Set<String>
        get() = byName.keys

    override fun location(name: String): LocationIfc? = byName[name]

    override fun requireLocation(name: String): LocationIfc = location(name)
        ?: throw IllegalArgumentException(
            "There is no place named ($name) in layout (${this.name}). It knows: " +
                    byName.keys.sorted().joinToString()
        )

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double =
        model.distance(fromLocation, toLocation)

    override fun isReachable(fromLocation: LocationIfc, toLocation: LocationIfc): Boolean =
        model.isValid(fromLocation) && model.isValid(toLocation)

    override fun toString(): String =
        "SpatialModelFleetSpace(${model.name}, ${byName.size} named places)"
}
