package ksl.modeling.fleet

/**
 * What one transport cost the load that asked for it.
 *
 * The three durations partition the wait: `waitForAssignment` runs from posting until a vehicle
 * committed, `waitForArrival` from there until the load was aboard, and `timeAboard` from there
 * until it was set down. Their sum is `totalTime`.
 *
 * The third is named for the interval rather than for the journey, matching `FleetSystem.timeAboard`
 * and for the same reason: the passive subsystem's `transportTime` means request to set-down, which
 * is the whole of `totalTime` here rather than this last leg of it.
 *
 * @param totalTime posting to delivery
 * @param waitForAssignment posting until a vehicle committed to the task
 * @param waitForArrival the commitment until the load was aboard
 * @param timeAboard aboard until set down, including any unloading delay
 * @param blockedTime how much of the above the vehicle spent unable to claim the space ahead of it
 * @param failedTime how much of the above the vehicle spent out of service -- broken down, waiting
 *   for a technician, or being pushed out of the way. Part of the intervals above rather than
 *   outside them: the load was waiting, or aboard, throughout. Zero for a fleet that cannot fail.
 * @param routeLength how far the vehicle travelled while carrying the load
 * @param vehicleName which vehicle carried it
 * @param numReassignments how many times the task was taken back and given to someone else. No
 *   counterpart in the passive subsystem's result: it is the observable trace of re-tasking, and
 *   is zero unless a policy revokes.
 */
data class FleetTransportResult(
    val totalTime: Double,
    val waitForAssignment: Double,
    val waitForArrival: Double,
    val timeAboard: Double,
    val blockedTime: Double,
    val failedTime: Double,
    val routeLength: Double,
    val vehicleName: String,
    val numReassignments: Int
) {
    override fun toString(): String =
        "FleetTransportResult(total=$totalTime, waitForAssignment=$waitForAssignment, " +
                "waitForArrival=$waitForArrival, aboard=$timeAboard, blocked=$blockedTime, " +
                "failed=$failedTime, " +
                "distance=$routeLength, vehicle=$vehicleName, reassignments=$numReassignments)"
}
