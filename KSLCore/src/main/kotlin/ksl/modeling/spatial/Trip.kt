package ksl.modeling.spatial

typealias TripIteratorIfc = Iterator<Movement>

enum class TripResult {
    COMPLETED, CANCELLED, COLLISION
}

data class Trip(
    val tripResult: TripResult,
    val origin: LocationIfc,
    val destination: LocationIfc,
    val currentLocation: LocationIfc,
    val timeStarted: Double,
    val originDepartureTime: Double,
    val distanceTravelled: Double
) {
    init {
        require(timeStarted >= 0.0) { "The time the trip started must be greater than zero" }
        require(originDepartureTime >= 0.0) { "The origin's departure time must be greater than zero" }
        require(distanceTravelled >= 0.0) { "The distance's travelled must be greater than zero" }
    }

    val completed: Boolean
        get() = tripResult == TripResult.COMPLETED

    val cancelled: Boolean
        get() = tripResult == TripResult.CANCELLED

    val collided: Boolean
        get() = tripResult == TripResult.COLLISION
}

data class Movement(
    val startingLocation: LocationIfc,
    val endingLocation: LocationIfc,
    val velocity: Double,
    val priority: Int
)

interface MovementControllerIfc : Iterable<Movement> {

}
