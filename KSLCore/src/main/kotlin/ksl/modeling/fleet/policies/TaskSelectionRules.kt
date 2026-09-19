package ksl.modeling.fleet.policies

import ksl.modeling.fleet.Dispatcher

/**
 * Determines the order in which an assignment policy is shown the outstanding tasks.
 *
 * The same relationship `RequestQ.requestSelectionRule` has to a resource: the rule may present
 * tasks in an order the queue discipline does not imply, so a later-arriving task can be offered
 * first without disturbing the queue itself or the waiting statistics it carries. Separating the two
 * matters because they answer different questions -- the discipline decides who is *at the front of
 * the line*, and this decides who is *offered first*, and a model may reasonably want a FIFO line
 * whose urgent work is still taken out of turn.
 *
 * A rule orders; it does not filter. Dropping a task here would make it invisible to every policy
 * while leaving it in the queue accruing waiting time, which is a starvation bug wearing a
 * respectable name. Implementations return a permutation of what they were given.
 */
fun interface TaskSelectionRuleIfc {

    /**
     * @param tasks the unassigned tasks, in queue order
     * @return the same tasks in the order a policy should consider them
     */
    fun order(tasks: List<Dispatcher.Task>): List<Dispatcher.Task>
}

/** Queue order, unchanged. The default, and the one that makes the queue discipline the whole rule. */
class FifoTaskSelection : TaskSelectionRuleIfc {
    override fun order(tasks: List<Dispatcher.Task>): List<Dispatcher.Task> = tasks
    override fun toString(): String = "FifoTaskSelection"
}

/**
 * Higher priority first, ties broken by how long the task has waited.
 *
 * The tie-break is not decoration. Priority classes are usually few and tasks many, so ties are the
 * common case rather than the exception, and a rule that left them to whatever order the list
 * happened to be in would make the model's behaviour depend on an implementation detail of the
 * queue. Falling back to age also means that within a priority class this degrades to FIFO, which is
 * what a modeller expects "priority" to mean.
 */
class ByPriorityTaskSelection : TaskSelectionRuleIfc {

    override fun order(tasks: List<Dispatcher.Task>): List<Dispatcher.Task> =
        tasks.sortedWith(compareByDescending<Dispatcher.Task> { it.priority }.thenBy { it.timeEnteredQueue })

    override fun toString(): String = "ByPriorityTaskSelection"
}

/**
 * Longest-waiting first, regardless of priority.
 *
 * Distinct from FIFO whenever the queue discipline is not itself first-in-first-out: with a ranked
 * queue the front of the line is not the oldest task, and a fleet run on a ranked discipline can
 * leave a low-ranked load waiting indefinitely. This is the rule that says no.
 */
class ByAgeTaskSelection : TaskSelectionRuleIfc {

    override fun order(tasks: List<Dispatcher.Task>): List<Dispatcher.Task> =
        tasks.sortedBy { it.timeEnteredQueue }

    override fun toString(): String = "ByAgeTaskSelection"
}
