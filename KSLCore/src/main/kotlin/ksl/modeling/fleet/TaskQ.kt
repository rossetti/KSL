package ksl.modeling.fleet

import ksl.modeling.fleet.policies.TaskSelectionRuleIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.queue.Queue
import ksl.simulation.ModelElement

/**
 * The waiting line: the one queue this subsystem reports.
 *
 * A typed request queue in the mould of [ksl.modeling.entity.RequestQ] and
 * [ksl.modeling.entity.ConveyorQ], owned by a [Dispatcher]. A task is enqueued when it is posted
 * and dequeued when a vehicle takes possession of the load, so its time in queue is the load's wait
 * for transport.
 *
 * The load itself waits in a hold queue, which reports nothing. That division is deliberate and is
 * the one `Conveyor` makes: a hold queue is how a suspended entity is found again and resumed, and
 * making it also the statistic conflates a mechanism with a measurement. What a modeller wants to
 * see, and what a dispatching policy ranks, is the *task* -- so the task is the thing that queues.
 */
class TaskQ @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) : Queue<Dispatcher.Task>(parent, name, discipline) {

    /**
     * Orders what an assignment policy sees. Null means the queue discipline alone decides.
     */
    var taskSelectionRule: TaskSelectionRuleIfc? = null

    /**
     * Removes the task from the queue and terminates the process of the entity waiting on it.
     *
     * For the modeller who wants outstanding transport requests abandoned rather than left hanging
     * -- for whatever reason their model has. It is **not** used for
     * end-of-replication teardown: that is done by `ProcessModel.afterReplication`, which
     * terminates every suspended entity without any help from this subsystem.
     *
     * @param task the task to abandon
     * @param waitStats if true the waiting time statistics are collected. The default is false,
     *   because a wait that was abandoned rather than served is not an observation of service.
     * @param afterTermination invoked once the process has been terminated
     */
    @JvmOverloads
    fun removeAndTerminate(
        task: Dispatcher.Task,
        waitStats: Boolean = false,
        afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null
    ) {
        // A vehicle already committed to this task is released first. Without that it goes on to the
        // pickup and tries to collect a load that has been terminated -- which surfaces much later,
        // from the control loop, as an illegal state transition with nothing pointing back here.
        // Refuses if the load is aboard: there is nowhere to set it down.
        task.dispatcher.releaseAnyVehicleFrom(task)
        remove(task, waitStats)
        task.transitionTo(TaskState.CANCELLED)
        val waiting = task.waitingEntity
        waiting?.terminateProcess(afterTermination)
    }

    /**
     * Removes and terminates every task waiting in the queue.
     *
     * @param waitStats if true the waiting time statistics are collected. The default is false.
     * @param afterTermination invoked once each process has been terminated
     */
    @JvmOverloads
    fun removeAllAndTerminate(
        waitStats: Boolean = false,
        afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null
    ) {
        while (isNotEmpty) {
            val task = peekNext()
            removeAndTerminate(task!!, waitStats, afterTermination)
        }
    }
}
