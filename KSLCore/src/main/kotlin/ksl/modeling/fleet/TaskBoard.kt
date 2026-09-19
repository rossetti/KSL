package ksl.modeling.fleet

/**
 * The read model an assignment policy is handed.
 *
 * A **view** over the dispatcher's [TaskQ], not a container. Two containers for one set of tasks is
 * two things to keep consistent, and a board a policy could mutate would let a policy violate "a
 * policy decides only" (`A7`) by accident rather than by intent. Every mutating operation lives on
 * [Dispatcher], which is the only object permitted to touch the queue.
 *
 * Not a `ModelElement`: it owns no state to reset between replications.
 */
class TaskBoard internal constructor(
    private val q: TaskQ
) {

    /** Everything outstanding, in queue order. */
    val tasks: List<Dispatcher.Task>
        get() = q.immutableList

    /**
     * The tasks no vehicle has committed to, in the order the selection rule chose.
     *
     * A policy should read this rather than filtering [tasks] itself, so that installing a
     * selection rule actually changes what policies see.
     */
    val unassigned: List<Dispatcher.Task>
        get() {
            val waiting = q.immutableList.filter { it.state == TaskState.POSTED }
            return q.taskSelectionRule?.order(waiting) ?: waiting
        }

    /** The tasks a vehicle has committed to but not yet collected. */
    val assigned: List<Dispatcher.Task>
        get() = q.immutableList.filter { it.state == TaskState.ASSIGNED }

    val numWaiting: Int
        get() = unassigned.size

    val numAssigned: Int
        get() = assigned.size

    /**
     * The longest-waiting unassigned task, or null. Cheap, because the queue is already ordered and
     * the selection rule has already been applied.
     */
    val oldest: Dispatcher.Task?
        get() = unassigned.firstOrNull()

    override fun toString(): String = "TaskBoard(waiting=$numWaiting, assigned=$numAssigned)"
}
