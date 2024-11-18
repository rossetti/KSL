package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.ModelElement


class TaskProcessingSystem(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    /**
     * Provides the ability to react to the completion of a task that was
     * started.
     */
    interface TaskCompletedIfc {

        /**
         * Called when the task is completed
         */
        fun taskCompleted(task: Task)
    }

    abstract inner class Task(val taskStarter: TaskCompletedIfc) : Entity() {
        //TODO task type??
        // start time, completion time, elapsed time

        /**
         *  The worker assigned to execute the task's process
         */
        var worker: Worker? = null

        /**
         * The deadline may be used by the worker to assist with task selection
         */
        var deadline: Double = Double.POSITIVE_INFINITY
            set(value) {
                require((value > 0.0) || (value.isInfinite())) { "The deadline must be > 0.0 or infinite" }
                field = value
            }

        abstract val taskProcess: KSLProcess

    }

    open inner class Worker(
        val taskQueue: Queue<Task>,
    ) : Entity() {
        //TODO consider a WorkerIfc interface

        var currentTask: Task? = null
        var previousTask: Task? = null

        fun receiveTask(task: Task, deadline: Double = Double.POSITIVE_INFINITY) {
            //TODO require that task is viable (not started, not completed)
            if (task.deadline != deadline) {
                task.deadline = deadline
            }
            task.worker = this
            taskQueue.enqueue(task)
            //TODO
            // if worker is idle then activate the worker's task processing
        }

        val taskProcessing = process("${this.name}_TaskProcessing") {
            while (hasNextTask()) {
                val nextTask = selectNextTask() ?: break
                taskQueue.remove(nextTask)
                //TODO set the state based on the task type
                currentTask = nextTask
                waitFor(nextTask.taskProcess)
                nextTask.taskStarter.taskCompleted(nextTask)
                previousTask = nextTask
                currentTask = null
                //TODO set the state (busy?)
            }
            //TODO set the state to idle?
        }

        /**
         *  Indicates true if selectNextTask() results in a non-null task
         */
        fun hasNextTask(): Boolean {
            return selectNextTask() != null
        }

        /**
         *  Finds the next task to work on. Does not remove the task
         *  from the task queue. If null, then a new task could not be selected.
         */
        open fun selectNextTask(): Task? {
            return taskQueue.peekNext()
        }
    }
}