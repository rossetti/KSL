package ksl.modeling.spatial

import ksl.modeling.entity.ProcessModel

typealias TripIteratorIfc = Iterator<Movement>

enum class TripStatus {
    COMPLETED, CANCELLED, IN_PROGRESS
}

data class Trip(
    val tripStatus: TripStatus,
    val origin: LocationIfc,
    val destination: LocationIfc,
    val currentLocation: LocationIfc,
    val timeStarted: Double,
    val timeEnded: Double,
    val distanceTravelled: Double,
    val cancellation: Cancellation? = null,
    val collision: Collision? = null
) {
    init {
        require(timeStarted >= 0.0) { "The time the trip started must be greater than or equal to zero" }
        require(timeEnded >= timeStarted) { "The time the trip ended must be greater than or equal to the time the trip started." }
        require(distanceTravelled >= 0.0) { "The distance's travelled must be greater than or equal to zero" }
    }

    val completed: Boolean
        get() = tripStatus == TripStatus.COMPLETED

    val cancelled: Boolean
        get() = tripStatus == TripStatus.CANCELLED

    val inProgress: Boolean
        get() = tripStatus == TripStatus.IN_PROGRESS
}

data class Movement(
    val startingLocation: LocationIfc,
    val endingLocation: LocationIfc,
    val velocity: Double,
    val priority: Int
)

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

interface MovementControllerIfc : Iterable<Movement> {
    //TODO the controller needs to determine the priority of the movements
}

class DefaultMovementController() : MovementControllerIfc {
    override fun iterator(): Iterator<Movement> {
        TODO("Not yet implemented")
    }
}
