package ksl.modeling.queue

import ksl.modeling.variable.DefaultReportingOptionIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc

interface QueueCIfc<T : ModelElement.QObject> : DefaultReportingOptionIfc {

    /**
     * Allows access to number in queue response information
     */
    val numInQ : TWResponseCIfc

    /**
     *  Allows access to time in queue response information
     */
    val timeInQ : ResponseCIfc

    /**
     *  The initial queue discipline. The initial discipline indicates
     *  the queue distribution that will be used at the beginning of each
     *  replication.  Changing the initial discipline during a replication
     *  will have no effect until the next replication.  WARNING:
     *  This will cause replications to have different disciplines and thus
     *  invalidate the concept of replications if used during a replication.
     *  Use this method only when the simulation is not running.
     */
    var initialDiscipline: Queue.Discipline

    /**
     * The current discipline for the queue
     */
    var currentDiscipline: Queue.Discipline

    /**
     * Indicates whether something was just enqueued or dequeued
     */
    val status: Queue.Status

    /**
     * Gets the size (number of elements) of the queue.
     */
    val size: Int

    /**
     * Returns whether the queue is empty.
     *
     * @return True if the queue is empty.
     */
    val isEmpty: Boolean

    /**
     * Returns true if the queue is not empty
     *
     * @return true if the queue is not empty
     */
    val isNotEmpty: Boolean

    /**
     *
     * @return unmodifiable view of the underlying list for the Queue
     */
    val immutableList: List<T>

    /**
     * Adds the supplied listener to this queue
     *
     * @param listener Must not be null, cannot already be added
     * @return true if added
     */
    fun addQueueListener(listener: QueueListenerIfc<T>): Boolean

    /**
     * Removes the supplied listener from this queue
     *
     * @param listener Must not be null
     * @return true if removed
     */
    fun removeQueueListener(listener: QueueListenerIfc<T>): Boolean

    /**
     *  Default option for whether waiting time statistics are collected
     *  upon removal of items from the queue
     */
    var waitTimeStatOption: Boolean

    /**
     * Returns true if this queue contains the specified element. More formally,
     * returns true if and only if this list contains at least one element e
     * such that (o==null ? e==null : o.equals(e)).
     *
     * @param qObj The object to be removed
     * @return True if the queue contains the specified element.
     */
    operator fun contains(qObj: T): Boolean

    /**
     *  The stream number to use if randomly selecting from the queue
     */
    val initialStreamNumber: Int
}