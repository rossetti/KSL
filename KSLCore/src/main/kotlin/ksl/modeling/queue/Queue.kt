/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.queue

import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import java.util.*
import java.util.function.Predicate

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
    val immutableList: List<ModelElement.QObjectIfc>

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

    val initialRandomSource: RandomIfc
}

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

/**
 * The Queue class provides the ability to hold entities (QObjects) within the
 * model.
 *
 * FIFO ensures first-in, first-out behavior. LIFO ensures
 * last-in, last-out behavior. RANKED ensures that each new element is
 * added such that the priority is maintained from the smallest first to the largest
 * priority last using the compareTo method of the QObject. Ties in priority
 * give preference to time of creation, then to order of creation.
 * RANDOM causes the elements to be randomly selected (uniformly).
 *
 * @param parent its parent
 * @param name The name of the queue
 * @param discipline The queuing discipline to be followed
 * @param <T> queues must hold sub-types of QObject
 */
open class Queue<T : ModelElement.QObject>(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) :
    ModelElement(parent, name), QueueCIfc<T>, QueueIfc<T> {

    override var waitTimeStatOption: Boolean = true

    override var defaultReportingOption: Boolean = true
        set(value) {
            myNumInQ.defaultReportingOption = value
            myTimeInQ.defaultReportingOption = value
            field = value
        }

    /**
     * ENQUEUED indicates that something was just enqueued DEQUEUED indicates
     * that something was just dequeued
     */
    enum class Status {
        ENQUEUED,  // something has entered the queue
        DEQUEUED,  // something has exited the queue
        IGNORE // ignore the exit or entrance
    }

    /**
     *  The method of ordering the queue
     */
    enum class Discipline {
        /**
         * first-in, first-out
         */
        FIFO,

        /**
         * last-in, last-out
         */
        LIFO,

        /**
         * randomly selected over the elements
         */
        RANDOM,

        /**
         * ordered by QObject priority, lower is higher-priority
         */
        RANKED
    }

    /**
     * The list of items in the queue.
     */
    protected val myList: MutableList<T> = mutableListOf()

    protected val myNumInQ: TWResponse = TWResponse(this, name = "${this.name}:NumInQ")
    override val numInQ : TWResponseCIfc
        get() = myNumInQ

    protected val myTimeInQ: Response = Response(this, name = "${this.name}:TimeInQ")
    override val timeInQ : ResponseCIfc
        get() = myTimeInQ

    /**
     *  The initial queue discipline. The initial discipline indicates
     *  the queue distribution that will be used at the beginning of each
     *  replication.  Changing the initial discipline during a replication
     *  will have no effect until the next replication.  WARNING:
     *  This will cause replications to have different disciplines and thus
     *  invalidate the concept of replications if used during a replication.
     *  Use this method only when the simulation is not running.
     */
    override var initialDiscipline: Discipline = discipline
        set(value) {
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial queue discipline of $name during replication ${model.currentReplicationNumber}." }
            }
            myInitialDiscipline = when (value) {
                Discipline.FIFO -> FIFODiscipline()
                Discipline.LIFO -> LIFODiscipline()
                Discipline.RANDOM -> RandomDiscipline()
                Discipline.RANKED -> RankedDiscipline()
            }
            field = value
        }

    /**
     * The current discipline for the queue
     */
    override var currentDiscipline: Discipline = discipline
        set(value) {
            if (field != value) {
                // actual change
                myDiscipline = when (value) {
                    Discipline.FIFO -> FIFODiscipline()
                    Discipline.LIFO -> LIFODiscipline()
                    Discipline.RANDOM -> RandomDiscipline()
                    Discipline.RANKED -> RankedDiscipline()
                }
                myDiscipline.switchDiscipline()
                field = value
            }
        }

    /**
     * The initial QueueDiscipline for this Queue.
     */
    private var myInitialDiscipline: QueueDiscipline = when (discipline) {
        Discipline.FIFO -> FIFODiscipline()
        Discipline.LIFO -> LIFODiscipline()
        Discipline.RANDOM -> RandomDiscipline()
        Discipline.RANKED -> RankedDiscipline()
    }

    /**
     * The current QueueDiscipline for this Queue.
     */
    private var myDiscipline: QueueDiscipline = myInitialDiscipline

    /**
     * Holds the listeners for this queue's enqueue and removeNext method use
     */
    protected val myQueueListeners: MutableList<QueueListenerIfc<T>> = mutableListOf()

    /**
     * Indicates whether something was just enqueued or dequeued
     */
    override var status: Status = Status.IGNORE
        protected set

    /**
     * can be called to initialize the queue The default behavior is to have the
     * queue cleared after the replication
     */
    override fun initialize() {
        super.initialize()
        if (currentDiscipline != initialDiscipline) {
            currentDiscipline = initialDiscipline
        }
        randomness = initialRandomSource
    }

    override fun afterReplication() {
        super.afterReplication()
        clear()
    }

    override fun removedFromModel() {
        super.removedFromModel()
        clear()
        myQueueListeners.clear()
        status = Status.IGNORE
    }

    /**
     *
     * @return unmodifiable view of the underlying list for the Queue
     */
    override val immutableList: List<QObjectIfc>
        get() = myList.toList()

    /**
     * Adds the supplied listener to this queue
     *
     * @param listener Must not be null, cannot already be added
     * @return true if added
     */
    override fun addQueueListener(listener: QueueListenerIfc<T>): Boolean {
        require(!myQueueListeners.contains(listener)) { "The queue already has the supplied listener." }
        return myQueueListeners.add(listener)
    }

    /**
     * Removes the supplied listener from this queue
     *
     * @param listener Must not be null
     * @return true if removed
     */
    override fun removeQueueListener(listener: QueueListenerIfc<T>): Boolean {
        return myQueueListeners.remove(listener)
    }

    /**
     * Places the QObject in the queue, with the specified priority
     * Automatically, updates the number in queue response variable.
     *
     * @param qObject - the QObject to enqueue
     */
    override fun enqueue(qObject: T){
        enqueue(qObject, qObject.priority, qObject.attachedObject)
    }

    /**
     * Places the QObject in the queue, with the specified priority
     * Automatically, updates the number in queue response variable.
     *
     * @param qObject - the QObject to enqueue
     * @param priority - the priority for ordering the object, lower has more priority
     * @param obj an Object to be "wrapped" and queued while the QObject is queued </S> */
    open fun enqueue(qObject: T, priority: Int = qObject.priority, obj: Any? = qObject.attachedObject) {
        qObject.enterQueue(this, time, priority, obj)
        myDiscipline.add(qObject)
        status = Status.ENQUEUED
        myNumInQ.increment()
        notifyQueueListeners(qObject)
    }

    /**
     * Returns a reference to the QObject representing the item that is next to
     * be removed from the queue according to the queue discipline that was
     * specified.
     *
     * @return a reference to the QObject object next item to be removed, or
     * null if the queue is empty
     */
    override fun peekNext(): T? {
        return myDiscipline.peekNext()
    }

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
    override fun removeNext(): T? {
        val qObj: T? = myDiscipline.removeNext()
        if (qObj != null) {
            qObj.exitQueue(time)
            status = Status.DEQUEUED
            myNumInQ.decrement()
            myTimeInQ.value = time - qObj.timeEnteredQueue
            notifyQueueListeners(qObj)
        }
        return qObj
    }

    /**
     * Returns true if this queue contains the specified element. More formally,
     * returns true if and only if this list contains at least one element e
     * such that (o==null ? e==null : o.equals(e)).
     *
     * @param qObj The object to be removed
     * @return True if the queue contains the specified element.
     */
    override operator fun contains(qObj: T): Boolean {
        return myList.contains(qObj)
    }

    /**
     * Returns true if this queue contains all the elements in the specified
     * collection WARNING: The collection should contain references to QObject's
     * otherwise it will certainly return false.
     *
     * @param c Collection c of items to check
     * @return True if the queue contains all the elements.
     */
    operator fun contains(c: Collection<T>): Boolean {
        return myList.containsAll(c)
    }

    /**
     * Returns the index in this queue of the first occurrence of the specified
     * element, or -1 if the queue does not contain this element. More formally,
     * returns the lowest index i such that (o==null ? get(i)==null :
     * o.equals(get(i))), or -1 if there is no such index.
     *
     * @param qObj The object to be found
     * @return The index (zero based) of the element or -1 if not found.
     */
    fun indexOf(qObj: T): Int {
        return myList.indexOf(qObj)
    }

    /**
     * Returns the index in this queue of the last occurrence of the specified
     * element, or -1 if the queue does not contain this element. More formally,
     * returns the lowest index i such that (o==null ? get(i)==null :
     * o.equals(get(i))), or -1 if there is no such index.
     *
     * @param qObj The object to be found
     * @return The (zero based) index or -1 if not found.
     */
    fun lastIndexOf(qObj: T): Int {
        return myList.lastIndexOf(qObj)
    }

    /**
     * @param predicate the predicate to count by
     */
    fun countBy(predicate: (T) -> Boolean): Int {
        return myList.filter(predicate).size
    }

    /**
     * @param predicate the predicate to count by
     */
    fun countBy(predicate: Predicate<T>): Int {
        return countBy(predicate::test)
    }

    /**
     * Finds all the QObjects in the Queue that satisfy the condition and
     * returns a list containing them.  The items are not removed
     * from the queue.
     *
     * @param predicate the condition for the search
     * @return the list of items that match the predicate
     */
    fun filter(predicate: (T) -> Boolean) : List<T>{
        return myList.filter(predicate)
    }

    /**
     * Finds all the QObjects in the Queue that satisfy the condition and
     * returns a list containing them.  The items are not removed
     * from the queue.
     *
     * @param predicate the condition for the search
     * @return the list of items that match the predicate
     */
    fun filter(predicate: Predicate<T>) : List<T>{
        return filter(predicate::test)
    }

    /**
     * Finds and removes all the QObjects in the Queue that satisfy the
     * condition and adds them to the deletedItems collection
     *
     * @param condition The condition to check
     * @param waitStats indicates whether waiting time statistics should be collected
     * @return a list of the removed items, which may be empty if none are removed
     */
    fun remove(predicate: (T) -> Boolean, waitStats: Boolean = waitTimeStatOption): MutableList<T> {
        val removedItems: MutableList<T> = mutableListOf()
        for (i in myList.indices) {
            val qo = myList[i]
            if (predicate.invoke(qo)) {
                removedItems.add(qo)
                remove(qo, waitStats)
            }
        }
        return removedItems
    }

    /**
     * Finds and removes all the QObjects in the Queue that satisfy the
     * condition and adds them to the deletedItems collection
     *
     * @param condition The condition to check
     * @param waitStats indicates whether waiting time statistics should be collected
     * @return a list of the removed items, which may be empty if none are removed
     */
    fun remove(condition: Predicate<T>, waitStats: Boolean = waitTimeStatOption): MutableList<T> {
        return remove(condition::test, waitStats)
    }

    /**
     * Removes the first occurrence in the queue of the specified element
     * Automatically collects waiting time statistics and number in queue
     * statistics. If the queue does not contain the element then it is
     * unchanged and false is returned
     *
     * @param qObj The object to be removed
     * @param waitStats Indicates whether waiting time statistics should be
     * collected on the removed item, true means collect statistics
     * @return True if the item was removed.
     */
    open fun remove(qObj: T, waitStats: Boolean = waitTimeStatOption): Boolean {
        return if (myList.remove(qObj)) {
            if (waitStats) {
                status = Status.DEQUEUED
                myTimeInQ.value = time - qObj.timeEnteredQueue
            } else {
                status = Status.IGNORE
            }
            myNumInQ.decrement()
            qObj.exitQueue(time)
            notifyQueueListeners(qObj)
            true
        } else {
            false
        }
    }

    /**
     * Removes the element at the specified position in this queue. Shifts any
     * subsequent elements to the left (subtracts one from their indices).
     * Returns the element that was removed from the list.
     *
     * Automatically, collects number in queue statistics. If waitStats flag is
     * true, then automatically collects the time in queue for the item and
     * includes it in the time in queue response variable.
     *
     * Throws an IndexOutOfBoundsException if the specified index is out of
     * range (index &lt; 0 || index &gt;= size()).
     *
     * @param index - the index of the element to be removed.
     * @param waitStats - true means collect waiting time statistics, false
     * means do not
     * @return the element previously at the specified position
     */
    fun remove(index: Int, waitStats: Boolean = waitTimeStatOption): T {
        val qObj = myList[index]
        remove(qObj, waitStats)
        return qObj
    }

    /**
     * Removes the QObject at the front of the queue Uses remove(int index)
     * where index = 0
     *
     * @return The first QObject in the queue or null if the list is empty
     */
    fun removeFirst(): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            remove(0)
        }
    }

    /**
     * Removes the QObject at the last index in the queue. Uses remove(int
     * index) where index is the size of the list - 1
     *
     * @return The last QObject in the queue or null if the list is empty
     */
    fun removeLast(): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            remove(myList.size - 1)
        }
    }

    /**
     * Returns the QObject at the front of the queue Depending on the queue
     * discipline this may not be the next QObject
     *
     * @return The first QObject in the queue or null if the list is empty
     */
    fun peekFirst(): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            myList[0]
        }
    }

    /**
     * Returns the QObject at the last index in the queue.
     *
     * @return The last QObject in the queue or null if the list is empty
     */
    fun peekLast(): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            myList[myList.size - 1]
        }
    }

    /**
     * Returns the QObject at the supplied index in the queue.
     *
     * Throws an IndexOutOfBoundsException if the specified index is out of
     * range (index &lt; 0 || index &gt;= size()).
     *
     * @param index the index to inspect
     * @return The QObject at index in the queue or null if the list is empty
     */
    fun peekAt(index: Int): T? {
        return if (myList.isEmpty()) {
            null
        } else {
            myList[index]
        }
    }

    /**
     * Returns the QObject at the supplied index in the queue.
     *
     * Throws an IndexOutOfBoundsException if the specified index is out of
     * range (index &lt; 0 || index &gt;= size()).
     *
     * @param index the index to inspect
     * @return The QObject at index in the queue
     */
    operator fun get(index: Int): T {
        return myList[index]
    }

    /**
     * Removes from this queue all the elements that are contained in the
     * specified collection The collection should contain references to objects
     * of type QObject that had been enqueued in this queue; otherwise, nothing
     * will be removed.
     *
     * Automatically, updates the number in queue variable If statFlag is true
     * it automatically collects time in queue statistics on removed items
     *
     * @param c The collection containing the QObject's to remove
     * @param statFlag true means collect statistics, false means do not
     * @return true if the queue changed as a result of the call
     */
    fun removeAll(c: Collection<T>, statFlag: Boolean = waitTimeStatOption): Boolean {
        var removedFlag = false
        for (qObj in c) {
            removedFlag = remove(qObj, statFlag)
        }
        return removedFlag
    }

    /**
     * Removes from this queue all the elements that are presented by iterating
     * through this iterator The iterator should be based on a collection that
     * contains references to objects of type QObject that had been enqueued in
     * this queue; otherwise, nothing will be removed.
     *
     * Automatically, updates the number in queue variable If statFlag is true
     * it automatically collects time in queue statistics on removed items
     *
     * @param c The iterator over the collection containing the QObject's to remove
     * @param statFlag true means collect statistics, false means do not
     * @return true if the queue changed as a result of the call
     */
    fun removeAll(c: Iterator<T>, statFlag: Boolean = waitTimeStatOption): Boolean {
        var removedFlag = false
        while (c.hasNext()) {
            val qo = c.next()
            removedFlag = remove(qo, statFlag)
        }
        return removedFlag
    }

    /**
     * Removes from this queue all the elements.
     *
     * Automatically, updates the number in queue variable If statFlag is true
     * it automatically collects time in queue statistics on removed items
     *
     * @param statFlag true means collect statistics, false means do not
     * @return true if the queue changed as a result of the call
     */
    fun removeAll(statFlag: Boolean = waitTimeStatOption): Boolean {
        var removedFlag = false
        while (isNotEmpty) {
            val qObj = peekNext()
            remove(qObj!!, statFlag)
            removedFlag = true
        }
        return removedFlag
    }

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
    override fun clear() {
        for (qObj in myList) {
            qObj.exitQueue(time)
        }
        myList.clear()
        status = Status.IGNORE
        myNumInQ.value = 0.0
        notifyQueueListeners(null)
    }

    /**
     * Returns an iterator (as specified by Collection ) over the elements in
     * the queue in proper sequence. The elements will be ordered according to
     * the state of the queue given the specified queue discipline.
     *
     * @return an iterator over the elements in the queue
     */
    override fun iterator(): Iterator<T> {
        return QueueListIterator()
    }

    /**
     * Returns an iterator (as specified by Collection ) over the elements in
     * the queue in proper sequence. The elements will be ordered according to
     * the state of the queue given the specified queue discipline.
     *
     * @return an iterator over the elements in the queue
     */
    fun listIterator(): ListIterator<T> {
        return QueueListIterator()
    }

    /**
     * Gets the size (number of elements) of the queue.
     */
    override val size: Int
        get() = myList.size

    /**
     * Returns whether the queue is empty.
     *
     * @return True if the queue is empty.
     */
    override val isEmpty: Boolean
        get() = myList.isEmpty()

    /**
     * Returns true if the queue is not empty
     *
     * @return true if the queue is not empty
     */
    override val isNotEmpty: Boolean
        get() = !isEmpty

    /**
     * Notifies any listeners that the queue changed
     *
     * @param qObject The qObject associated with the notification
     */
    protected fun notifyQueueListeners(qObject: T?) {
        for (ql in myQueueListeners) {
            ql.update(status, this, qObject)
        }
    }

    /**
     * RandomIfc provides a reference to the underlying source of randomness
     * to initialize each replication.
     * Controls the underlying RandomIfc source for the RandomVariable. This is the
     * source to which each replication will be initialized.  This is only used
     * when the replication is initialized. Changing the reference has no effect
     * during a replication, since the random variable will continue to use
     * the reference returned by property randomSource.
     */
    final override var initialRandomSource: RandomIfc = defaultUniformRV
        set(value) {
            require(model.isNotRunning) {"The model should not be running when changing the initial random source"}
            field = value
        }

    /**
     * If the Queue uses randomness, this controls it. By default, it uses
     * the model's global source of uniform random variates set via the initialRandomSource property
     * if the user decides to change it, replications may not be replications.  Every
     * replication will start with the same initial random source as per the initialRandomSource property.
     */
    var randomness: RandomIfc = initialRandomSource

    private inner class QueueListIterator : ListIterator<T> {
        private var myIterator: ListIterator<T> = myList.listIterator()

        override fun hasNext(): Boolean {
            return myIterator.hasNext()
        }

        override fun hasPrevious(): Boolean {
            return myIterator.hasPrevious()
        }

        override fun next(): T {
            return myIterator.next()
        }

        override fun nextIndex(): Int {
            return myIterator.nextIndex()
        }

        override fun previous(): T {
            return myIterator.previous()
        }

        override fun previousIndex(): Int {
            return myIterator.previousIndex()
        }
    }

    internal fun priorityChanged() {
        myDiscipline.priorityChanged()
    }

    abstract inner class QueueDiscipline {
        /**
         * Adds the specified element to the proper location in the queue
         * @param qObject The element to be added to the queue
         */
        internal abstract fun add(qObject: T)

        /**
         * Returns a reference to the next QObject to be removed from the
         * queue. The item is not removed from the list.
         *
         * @return The QObject that is next, or null if the list is empty
         */
        internal abstract fun peekNext(): T?

        /**
         * Removes the next item from the queue according to the discipline
         *
         * @return A reference to the QObject item that was removed or null
         * if the queue is empty
         */
        internal abstract fun removeNext(): T?

        /**
         * Provides a "hook" method to be called when switching from one
         * discipline to another The implementor should use this method to
         * ensure that the underlying queue is in a state that allows it to be
         * managed by this queue discipline
         *
         */
        internal abstract fun switchDiscipline()

        /**
         * The supplied QObject has had its priority changed. This
         * method is called to allow the queue to be re-ordered.
         */
        internal abstract fun priorityChanged()

    }

    inner class FIFODiscipline : QueueDiscipline() {
        override fun add(qObject: T) {
            myList.add(qObject)
        }

        override fun peekNext(): T? {
            return if (myList.isEmpty()) {
                null
            } else myList[0]
        }

        override fun removeNext(): T? {
            return if (myList.isEmpty()) {
                null
            } else myList.removeAt(0)
        }

        override fun switchDiscipline() {
        }

        override fun priorityChanged() {
        }
    }

    private inner class LIFODiscipline : QueueDiscipline() {
        override fun add(qObject: T) {
            myList.add(qObject)
        }

        override fun peekNext(): T? {
            return if (myList.isEmpty()) {
                null
            } else myList[myList.size - 1]
        }

        override fun removeNext(): T? {
            return if (myList.isEmpty()) {
                null
            } else myList.removeAt(myList.size - 1)
        }

        override fun switchDiscipline() {
        }

        override fun priorityChanged() {
        }
    }

    private inner class RankedDiscipline : QueueDiscipline() {
        override fun add(qObject: T) {

            // nothing in queue, just add it, and return
            if (myList.isEmpty()) {
                myList.add(qObject)
                return
            }

            // might as well check for worse case, if larger than the largest then put it at the end and return
            if (qObject.compareTo(myList[myList.size - 1]) >= 0) {
                myList.add(qObject)
                return
            }

            // now iterate through the list
            val i: ListIterator<T> = myList.listIterator()
            while (i.hasNext()) {
                if (qObject.compareTo(i.next()) < 0) {
                    // next() move the iterator forward, if it is < what was returned by next(), then it
                    // must be inserted at the previous index
                    myList.add(i.previousIndex(), qObject)
                    return
                }
            }
        }

        override fun peekNext(): T? {
            return if (myList.isEmpty()) {
                null
            } else myList[0]
        }

        override fun removeNext(): T? {
            return if (myList.isEmpty()) {
                null
            } else myList.removeAt(0)
        }

        /**
         * Since regardless of the former queue discipline, the ranked queue
         * discipline must ensure that the underlying queue is in a ranked state
         * after the change over.
         *
         *
         */
        override fun switchDiscipline() {
            myList.sort()
        }

        override fun priorityChanged() {
            myList.sort()
        }

    }

    private inner class RandomDiscipline : QueueDiscipline() {

        private var myNext = 0

        override fun add(qObject: T) {
            myList.add(qObject)
        }

        override fun peekNext(): T? {
            if (myList.isEmpty()) {
                return null
            }
            myNext = if (myList.size == 1) {
                0
            } else {
                randomness.rnStream.randInt(0, myList.size - 1)
            }
            return myList[myNext] // randomly pick it from the range available
        }

        override fun removeNext(): T? {
            if (myList.isEmpty()) {
                return null
            }
            peekNext() // sets the next randomly
            return myList.removeAt(myNext) // now returns the next
        }

        override fun switchDiscipline() {
        }

        override fun priorityChanged() {
        }
    }
}