package ksl.modeling.fleet

import ksl.modeling.fleet.exceptions.FleetAssignmentException

/** Where a commitment has got to. */
enum class AssignmentState { ASSIGNED, IN_PROGRESS, COMPLETED, REVOKED }

/**
 * A vehicle's commitment to a task.
 *
 * A first-class object rather than a field on either party, because it must be revocable while a
 * vehicle is still on its way to collect, and because it must record the terms it was won on when
 * it was won in an auction. Neither of those fits in a pointer.
 */
class Assignment internal constructor(
    val vehicle: FleetVehicle,
    val task: Dispatcher.Task,
    val madeAt: Double,
    val decidedBy: String,
    val terms: Double? = null
) {

    var state: AssignmentState = AssignmentState.ASSIGNED
        internal set

    /** True only before the load is aboard (`A4`). */
    val isRevocable: Boolean
        get() = state == AssignmentState.ASSIGNED

    internal fun requireRevocable() {
        if (!isRevocable) {
            throw FleetAssignmentException(
                "The assignment of task (${task.name}) to vehicle (${vehicle.name}) cannot be " +
                        "revoked: it is $state, and an assignment may only be revoked while the " +
                        "vehicle is still on its way to collect the load (A4)."
            )
        }
    }

    override fun toString(): String =
        "Assignment(${vehicle.name} -> ${task.name}, $state, madeAt=$madeAt, by=$decidedBy)"
}

/**
 * What an assignment policy returns.
 *
 * A policy cannot construct an [Assignment] -- its constructor is internal -- which is how "a
 * policy decides only" (`A7`) is enforced by the type system rather than by a rule someone has to
 * remember. A proposal is inert: it names a pairing and nothing happens until the dispatcher acts
 * on it.
 */
data class AssignmentProposal(
    val vehicle: FleetVehicle,
    val task: Dispatcher.Task,
    val terms: Double? = null
)
