package ksl.modeling.queue

import ksl.simulation.ModelElement

interface QueueIfc<T : ModelElement.QObject> : Iterable<T>{
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
     * Places the QObject in the queue, with the specified priority
     * Automatically, updates the number in queue response variable.
     *
     * @param qObject - the QObject to enqueue
     */
    fun enqueue(qObject: T)

    /**
     * Returns a reference to the QObject representing the item that is next to
     * be removed from the queue according to the queue discipline that was
     * specified.
     *
     * @return a reference to the QObject object next item to be removed, or
     * null if the queue is empty
     */
    fun peekNext(): T?

    /**
     * Removes the next item from the queue according to the queue discipline
     * that was specified. Returns a reference to the QObject representing the
     * item that was removed
     *
     * Automatically, collects the time in queue for the item and includes it in
     * the time in queue response variable.
     *
     * Automatically, updates the number in queue response variable.
     *
     * @return a reference to the QObject object, or null if the queue is empty
     */
    fun removeNext(): T?

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
     * Removes all the elements from this collection
     *
     * WARNING: This method DOES NOT record the time in queue for the cleared
     * items if the user wants this functionality, it can be accomplished using
     * the remove(int index) method, while looping through the items to remove
     * Listeners are notified of the queue change with IGNORE
     *
     * This method simply clears the underlying data structure that holds the objects
     */
    fun clear()
}