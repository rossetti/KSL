package ksl.modeling.spatial

import ksl.modeling.entity.ProcessModel

typealias TripIteratorIfc = Iterator<Movement>

enum class TripStatus {
    COMPLETED, CANCELLED, IN_PROGRESS
}

interface MoverIfc {
    val destination: LocationIfc
    val currentLocation: LocationIfc
    val currentVelocity: Double
}

interface TripIfc : MoverIfc {
    val tripStatus: TripStatus
    val origin: LocationIfc
    override val destination: LocationIfc
    override val currentLocation: LocationIfc
    val timeStarted: Double
    val timeEnded: Double
    val distanceTravelled: Double
    val cancellation: Cancellation? //TODO this needs more thought
}

/**
 *  A movement represents a potential step towards an ending location from
 *  a starting location. The velocity of a movement may be 0.0. This indicates
 *  that no movement should occur. It does not necessarily indicate that the
 *  distance is 0.0 between the starting location and the ending location.
 *  @param startingLocation the location from which to start the movement
 *  @param endingLocation the location at which the movement should end
 *  @param velocity the intended velocity of the movement, which may be 0.0, which
 *  indicates that the movement should not happen (i.e. no movement).
 *  @param priority the priority for the movement when with respect to the event scheduling of
 *  the end of the movement.
 */
data class Movement(
    val startingLocation: LocationIfc,
    val endingLocation: LocationIfc,
    val velocity: Double,
    val priority: Int
){
    init {
        require(velocity >= 0.0) {"The velocity must be >= 0.0"}
    }

    val distance: Double
        get() = startingLocation.distanceTo(endingLocation)
}

data class Collision(
    val location: LocationIfc,
    val collidingEntity: ProcessModel.Entity,
    val entities: Set<ProcessModel.Entity>,
    val timeOfCollision: Double,
    val movement: Movement
)

data class Cancellation(
    val timeOfCancellation: Double,
    val reason: String? = null,
    val lastMovement: Movement? = null
)

fun interface MovementControllerIfc {
    fun computeMovement(mover: MoverIfc) : Movement
}

class DefaultMovementController() : MovementControllerIfc {
    override fun computeMovement(mover: MoverIfc): Movement {
        TODO("Not yet implemented")
    }

}
