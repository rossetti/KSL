package ksl.modeling.fleet

/** Where a task has got to. Transitions are checked; an illegal one raises (`A1`). */
enum class TaskState {

    /** Waiting on the dispatcher's board for a vehicle. */
    POSTED,

    /** A vehicle has committed to it but has not yet taken possession of the load. */
    ASSIGNED,

    /** The load is aboard. Beyond this point the assignment cannot be revoked (`A4`). */
    IN_PROGRESS,

    /** Delivered. Terminal. */
    COMPLETED,

    /** Abandoned before delivery. Terminal. */
    CANCELLED
}

/**
 * What a vehicle is doing for itself when it is not carrying anything.
 *
 * Sealed rather than an enum so that adding a kind is a compile error at every exhaustive `when`
 * that must handle it. This is where the exhaustiveness argument actually bites: `Task` itself
 * cannot be sealed, because it must be a `QObject` and `QObject` is an inner class, and Kotlin
 * refuses `sealed inner`. `Task`'s hierarchy is closed by construction -- a transport, an errand
 * and a line cycle, all created by the dispatcher -- whereas the service kinds are the thing later
 * work extends.
 */
sealed class ServiceKind {

    /** Move somewhere and wait there. */
    data object Reposition : ServiceKind()

    // Charge arrives with the battery seam; Repair with the failure seam.
}
