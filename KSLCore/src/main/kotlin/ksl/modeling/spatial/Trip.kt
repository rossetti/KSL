package ksl.modeling.spatial

import ksl.modeling.entity.ProcessModel.Companion.MOVE_PRIORITY

enum class TripStatus {
    COMPLETED, STOPPED, IN_PROGRESS
}

interface MoverIfc : VelocityIfc {
    val spatialModel: SpatialModel
    val destination: LocationIfc
    val currentLocation: LocationIfc
    val currentVelocity: Double
    val atDestination: Boolean
        get() = currentLocation.isLocationEqualTo(destination)
}

interface TripIfc : MoverIfc {
    val tripStatus: TripStatus
    val origin: LocationIfc
    val timeStarted: Double
    val timeEnded: Double
    val distanceTravelled: Double
    val lastMovement: Movement?
    override val currentVelocity: Double
        get() = lastMovement?.velocity ?: 0.0
}

enum class MovementType {
    /**
     *  Intermediate indicates that the movement is part of trip that is in progress.
     */
    INTERMEDIATE,

    /**
     *  Last indicates that the movement is the last movement of a completed trip.
     */
    LAST,

    /**
     *  Collision indicates that the movement may result in a collision. It does
     *  not mean that the collision will occur.
     */
    COLLISION,

    /**
     *  Stopped indicates that the movement caused the trip to stop such that it
     *  will not complete.
     */
    STOPPED
}

/**
 *  A movement represents a potential step towards an ending location from
 *  a starting location. The velocity of a movement cannot be 0.0. If a movement
 *  is to occur between the starting location and the ending location it must
 *  have a positive velocity. The ending location of the movement cannot be the same
 *  as the starting location because there would be no need for movement.
 *
 *  @param movementType the type of movement as specified by the MovementType enum.
 *  @param startingLocation the location from which to start the movement
 *  @param endingLocation the location at which the movement should end. The ending location of
 *  a movement cannot be the same as the starting location.
 *  @param velocity the intended velocity of the movement, which must be greater than 0.0
 *  @param priority the priority for the movement when with respect to the event scheduling of
 *  the end of the movement.
 */
data class Movement(
    val movementType: MovementType,
    val startingLocation: LocationIfc,
    val endingLocation: LocationIfc,
    val velocity: Double,
    val priority: Int = MOVE_PRIORITY
) {
    init {
        require(!endingLocation.isLocationEqualTo(startingLocation)) {"The ending location of a movement cannot be the same as the starting location."}
        require(velocity > 0.0) { "The velocity must be > 0.0" }
    }

    val distance: Double
        get() = startingLocation.distanceTo(endingLocation)
}

//data class Collision(
//    val location: LocationIfc,
//    val collidingEntity: ProcessModel.Entity,
//    val entities: Set<ProcessModel.Entity>,
//    val timeOfCollision: Double,
//    val movement: Movement
//)

//data class Cancellation(
//    val timeOfCancellation: Double,
//    val reason: String? = null,
//    val lastMovement: Movement? = null
//)

/**
 *  A returned null movement indicates no movement for the mover and must
 *  be handled accordingly.
 */
fun interface MovementControllerIfc {
    fun computeMovement(mover: MoverIfc): Movement?
}

/**
 *  The default movement controller computes a movement that will take the mover
 *  directly from its current location to the desired location in one movement.
 *  If the mover is already at its destination, then no movement occurs. That is,
 *  a null movement is returned.
 */
object DefaultMovementController : MovementControllerIfc {
    override fun computeMovement(mover: MoverIfc): Movement? {
        // handle the case where current location == destination
        if (mover.atDestination){
            return null
        }
        // assume that the mover moves directly to the destination
        val m = Movement(
            MovementType.LAST,
            mover.currentLocation,
            mover.destination,
            mover.velocity.value,
            MOVE_PRIORITY
        )
        return m
    }
}
