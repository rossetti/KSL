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

package ksl.simulation

import ksl.modeling.elements.RandomElementIfc
import ksl.modeling.queue.Queue
import ksl.modeling.spatial.SpatialModel
import ksl.modeling.variable.*
import ksl.observers.ModelElementObserver
import ksl.utilities.GetValueIfc
import ksl.utilities.IdentityIfc
import ksl.utilities.NameIfc
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.modeling.station.QObjectReceiverIfc
import ksl.modeling.station.QObjectSenderIfc

private var elementCounter: Int = 0

/**
 * incremented to give a running total of the number of model QObject
 * created
 */
private var qObjCounter: Long = 0

abstract class ModelElement internal constructor(name: String? = null) : IdentityIfc {
    //TODO spatial model stuff
    //TODO change parent model element method, was in JSL, can/should it be in KSL

    /**
     * A set of constants for indicating model element status to observers of
     * basic model element changes
     */
    enum class Status {
        NONE, BEFORE_EXPERIMENT, BEFORE_REPLICATION, INITIALIZED, CONDITIONAL_ACTION_REGISTRATION, MONTE_CARLO, WARMUP,
        UPDATE, TIMED_UPDATE, REPLICATION_ENDED, AFTER_REPLICATION, AFTER_EXPERIMENT,
        MODEL_ELEMENT_ADDED, MODEL_ELEMENT_REMOVED, REMOVED_FROM_MODEL
    }

    /**
     *  A definition of default time unit conversions.  MILLISECOND = 1.0, SECOND = 1000.0,
     *  MINUTE = 60*SECOND, HOUR = 60*MINUTE, DAY = 24*HOUR, WEEK = 7*DAY, MONTH = 30.0*DAY,
     *  YEAR = 365*DAY
     */
    enum class TimeUnit(val value: Double) {
        MILLISECOND(1.0), SECOND(1000.0), MINUTE(60.0 * SECOND.value),
        HOUR(60.0 * MINUTE.value), DAY(24.0 * HOUR.value), WEEK(7.0 * DAY.value),
        MONTH(30.0 * DAY.value), YEAR(365.0 * DAY.value)
        //TODO consider conversion functions
    }

    init {
        elementCounter = elementCounter + 1
    }

    private val modelElementObservers = mutableListOf<ModelElementObserver>()

    override val id: Int = elementCounter

    final override val name: String = makeName(name)
    private fun makeName(str: String?): String {
        return if (str == null) {
            // no name is being passed, construct a default name
            var s = this::class.simpleName!!
            val k = s.lastIndexOf(".")
            if (k != -1) {
                s = s.substring(k + 1)
            }
            s + "_" + id
        } else {
            // the name of a model element cannot contain a "." character
            str.replace(".", "_")
        }
    }

    override var label: String? = null
        get() {
            return if (field == null) name else field
        }

    /**
     *  Indicates the previous status of the model element for observers of ModelElement.Status
     *  This allows the transition to be noted by observers
     */
    var previousStatus: Status = Status.NONE
        private set

    /**
     *  Indicates the current status of the model element for observers of ModelElement.Status
     */
    var currentStatus: Status = Status.NONE
        internal set(value) {
            previousStatus = field
            field = value
            logger.trace { "ModelElement: $name changing status from previous: $previousStatus to current: $field" }
            notifyModelElementObservers(field)
        }

    /**
     * the left traversal count for pre-order traversal of the model element tree
     */
    var leftTraversalCount = 0
        protected set

    /**
     * the right traversal count for pre-order traversal of the model element tree
     */
    var rightTraversalCount = 0
        protected set

    /**
     * A flag to control whether the model element reacts to before
     * experiment actions.
     */
    var beforeExperimentOption = true

    /**
     * A flag to control whether the model element reacts to before
     * replication actions.
     */
    var beforeReplicationOption = true

    /**
     * A flag to control whether the model element participates in monte
     * carlo actions.
     */
    var monteCarloOption = false

    /**
     * A flag to control whether the model element reacts to
     * initialization actions
     */
    var initializationOption = true

    /**
     * A flag to control whether the model element reacts to end
     * replication actions.
     */
    var replicationEndedOption = true

    /**
     * A flag to control whether the model element reacts to after
     * replication actions.
     */
    var afterReplicationOption = true

    /**
     * A flag to control whether the model element reacts to after
     * experiment actions.
     */
    var afterExperimentOption = true

    /**
     * Specifies if this model element will be warmed up when the warmup action
     * occurs for its parent.
     * The warm-up flag  indicates whether this model element
     * will be warmed up when its parent warm up event/action occurs. The
     * default value for all model elements is true. A value of true implies
     * that the model element allows its parent's warm up event to call the warm-up
     * action. A value of false implies that the model element does not allow
     * its parent's warm up event to call the warm-up action. False does not
     * necessarily mean that the model element will not be warmed up. It may,
     * through the use of the lengthOfWarmUp property, have its own warm up
     * event and action.
     */
    var warmUpOption = true

    /**
     * Specifies whether this model element participates in time update
     * event specified by its parent
     */
    var timedUpdateOption = true

    /**
     * A collection containing the first level children of this model element
     */
    protected val myModelElements: MutableList<ModelElement> = mutableListOf()

    /**
     * The parent of this model element
     */
    internal var myParentModelElement: ModelElement? = null

    /**
     * A reference to the overall model containing all model elements.
     */
    internal lateinit var myModel: Model

    /**
     *  the parent of this model element
     */
    protected val parent
        get() = myParentModelElement

    /**
     * the model that contains this element
     */
    val model
        get() = myModel

    protected open var mySpatialModel: SpatialModel? = parent?.spatialModel

    /**
     * The spatial model associated with this model element. By default, each model element
     * uses its parent model element's spatial model unless changed via this property.
     * This changes the spatial model for this model element and no others.
     */
    var spatialModel: SpatialModel
        get() {
            return if (mySpatialModel == null) {
                parent!!.spatialModel
            } else {
                mySpatialModel!!
            }
        }
        set(value) {
            mySpatialModel = value
        }

    /**
     *  A global uniform random number source
     */
    protected val defaultUniformRV: RandomVariable
        get() = myModel.myDefaultUniformRV

    /**
     *  the executive that is executing the events
     */
    protected val executive: Executive
        get() = model.myExecutive

    /**
     * Causes the current replication to stop processing events.
     * @param msg an optional string message can be supplied to inform output about the reason for the stoppage
     */
    protected fun stopReplication(msg:  String? = null){
        executive.stop("time $time> User stopped the replication: $msg" )
    }

    //TODO revisit myDefaultEntityType when working on process modeling
//    protected val defaultEntityType: EntityType
//        get() = model.myDefaultEntityType

    /**
     * The action listener that reacts to the warm-up event.
     */
    private var myWarmUpEventAction: WarmUpEventAction? = null

    /**
     * A reference to the warm-up event
     */
    protected var warmUpEvent: KSLEvent<Nothing>? = null

    /**
     * Indicates whether the warm-up action occurred sometime during the
     * simulation for this model element. False indicates that the warm-up action has not occurred
     */
    var warmUpIndicator = false
        protected set

    /**
     * Specifies the priority of this model element's warm up event.
     */
    var warmUpPriority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY

    /**
     * The length of time from the start of the simulation to the warm-up event.
     * Sets the length of the warm-up for this model element.
     * <p>
     * Setting the length of the warm up to 0.0 will set the warm-up option flag
     * to true.
     * <p>
     * This is based on the assumption that a zero length warm up implies that
     * the model element's parent warm up event will take care of the warm-up
     * action. If this is not the case, then setting the warmUpOption to false after
     * setting the length of the warm up to 0.0, will cause the model element to
     * not have a warmup.
     * <p>
     * In general, there is not a need to set the length of the warm up to zero
     * unless the reactor is resetting the value after explicitly specifying it
     * for a replication. The default value of the warm-up length is zero. A zero
     * length warm up will not cause a separate event to be scheduled. The
     * default warm up flag option starts as true, which implies that the model
     * element lets its parent's warm up event take care of the warm-up action.
     * <p>
     * Setting the length of the warm-up &gt; 0.0, will set the warm-up option
     * flag to false.
     * <p>
     * Prior to each replication the specified warm-up length will be checked to
     * see if it is greater than zero. if the length of the warm-up is greater
     * than zero, it is checked to see if it is less than the simulation run
     * length. If so, it is assumed that the model element wants its own warm up
     * event scheduled. It is also assumed that the model element does not
     * depend on its parent for a warm-up action. The warm-up option flag will
     * be set to false and a separate warm up event will be scheduled for the
     * model element.
     */
    protected var individualElementWarmUpLength = 0.0 // zero means no warm up
        protected set(warmUpTime) {
            require(warmUpTime >= 0.0) { "Individual element warm up event time must be >= 0.0" }
            field = warmUpTime
            warmUpOption = (field == 0.0)
        }

    /**
     * The action listener that reacts to the timed update event.
     */
    private var myTimedUpdateActionListener: TimedUpdateEventAction? = null

    /**
     * A reference to the TimedUpdate event.
     */
    protected var timedUpdateEvent: KSLEvent<Nothing>? = null

    /**
     * Specifies the havingPriority of this model element's timed update event.
     */
    var timedUpdatePriority = KSLEvent.DEFAULT_TIMED_EVENT_PRIORITY

    /**
     * The time interval between TimedUpdate events. The default is zero,
     * indicating no timed update
     */
    var timedUpdateInterval = 0.0
        set(value) {
            require(value > 0.0) { "Time update interval must be > 0.0" }
            field = value
        }

    constructor(parent: ModelElement, name: String? = null) : this(name) {
        // should not be leaking this
        // adds the model element to the parent and also set this element's parent
        parent.addModelElement(this)
        // sets this element's model to the model of its parent, everyone is in the same model
        myModel = parent.myModel
        // tells the model to add this element to the overall model element map
        myModel.addToModelElementMap(this)
    }

    /**
     * Returns a string representation of the model element and its child model
     * elements. Useful for realizing the model element hierarchy.
     *
     * &lt;type&gt; getClass().getSimpleName() &lt;\type&gt;
     * &lt;name&gt; getName() &lt;\name&gt; child elements here, etc.
     * &lt;/modelelement&gt;
     *
     * @return the model element as a string
     */
    val modelElementsAsString: String
        get() {
            val sb = StringBuilder()
            getModelElementsAsString(sb)
            return sb.toString()
        }

    /**
     *  The current simulation time
     */
    val time
        get() = executive.currentTime * model.baseTimeUnit.value //TODO check if I should multiply by base time unit

    /**
     * Fills up the supplied StringBuilder carrying a string representation of
     * the
     * model element and its child model elements Useful for realizing the model
     * element hierarchy.
     *
     * &lt;modelelement&gt;
     * &lt;type&gt; getClass().getSimpleName() &lt;\type&gt;
     * &lt;name&gt; getName() &lt;\name&gt; child elements here, etc.
     * &lt;/modelelement&gt;
     *
     * @param sb to hold the model element as a string
     * @param n  The starting level of indentation for the model elements
     */
    fun getModelElementsAsString(sb: StringBuilder, n: Int = 0) {
        indent(sb, n)
        sb.append("<modelelement>")
        sb.appendLine()
        indent(sb, n + 1)
        sb.append("<type>")
        sb.append(javaClass.simpleName)
        sb.append("</type>")
        sb.appendLine()
        indent(sb, n + 1)
        sb.append("<name>")
        sb.append(name)
        sb.append("</name>")
        sb.appendLine()
        indent(sb, n + 1)
        sb.append("<id>")
        sb.append(id)
        sb.append("</id>")
        sb.appendLine()
        for (m in myModelElements) {
            m.getModelElementsAsString(sb, n + 1)
        }
        indent(sb, n)
        sb.append("</modelelement>")
        sb.appendLine()
    }

    /**
     * Add spaces representing the level of indention
     *
     * @param sb holds the stuff to be indented
     * @param n  level of indentation
     */
    private fun indent(sb: StringBuilder, n: Int) {
        for (i in 1..n) {
            sb.append("  ")
        }
    }

    /**
     *  Checks if current status is the supplied status
     */
    fun isStatus(status: Status): Boolean {
        return status == currentStatus
    }

    /**
     * Sets the before experiment option of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in setup.
     */
    fun setBeforeExperimentOptionForModelElements(flag: Boolean) {
        beforeExperimentOption = flag
        for (m in myModelElements) {
            m.setBeforeExperimentOptionForModelElements(flag)
        }
    }

    /**
     * Sets the before replication flag of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setBeforeReplicationOptionForModelElements(flag: Boolean) {
        beforeReplicationOption = flag
        for (m in myModelElements) {
            m.setBeforeReplicationOptionForModelElements(flag)
        }
    }

    /**
     * Sets the monte carlo option flag of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setMonteCarloOptionForModelElements(flag: Boolean) {
        monteCarloOption = flag
        for (m in myModelElements) {
            m.setMonteCarloOptionForModelElements(flag)
        }
    }

    /**
     * Sets the initialization option of all model elements (children) contained
     * by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setInitializationOptionForModelElements(flag: Boolean) {
        initializationOption = flag
        for (m in myModelElements) {
            m.setInitializationOptionForModelElements(flag)
        }
    }

    /**
     * Fills a StringBuilder carrying the model element names in the order that
     * they will be initialized
     *
     * @param sb the StringBuilder to fill
     */
    fun initializationOrder(sb: StringBuilder) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                m.initializationOrder(sb)
            }
        }
        sb.append(name)
        sb.appendLine()
    }

    /**
     * Sets the warm-up option flag of all model elements (children) contained
     * by this model element.
     *
     * @param warmUpFlag True means that they participate in the default action
     */
    fun setWarmUpOptionForModelElements(warmUpFlag: Boolean) {
        warmUpOption = warmUpFlag
        for (m in myModelElements) {
            m.setWarmUpOptionForModelElements(warmUpFlag)
        }
    }

    /**
     * Sets the timed update option flag of all model elements (children)
     * contained by this model element.
     *
     * @param timedUpdateOption True means that they participate in the default
     * action
     */
    fun setTimedUpdateOptionForModelElements(timedUpdateOption: Boolean) {
        this.timedUpdateOption = timedUpdateOption
        for (m in myModelElements) {
            m.setTimedUpdateOptionForModelElements(timedUpdateOption)
        }
    }

    /**
     * Sets the end replication option flag of all model elements (children)
     * contained by this model element. Determines whether the
     * replicationEnded() method will be called
     *
     * @param flag True means that they participate in the default action
     */
    fun setReplicationEndedOptionForModelElements(flag: Boolean) {
        replicationEndedOption = flag
        for (m in myModelElements) {
            m.setReplicationEndedOptionForModelElements(flag)
        }
    }

    /**
     * Sets the after replication flag of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setAfterReplicationOptionForModelElements(flag: Boolean) {
        afterReplicationOption = flag
        for (m in myModelElements) {
            m.setAfterReplicationOptionForModelElements(flag)
        }
    }

    /**
     * Sets the after experiment option of all model elements (children)
     * contained by this model element.
     *
     * @param option True means that they participate.
     */
    fun setAfterExperimentOptionForModelElements(option: Boolean) {
        afterExperimentOption = option
        for (m in myModelElements) {
            m.setAfterExperimentOptionForModelElements(option)
        }
    }

    /**
     * This method is called from the constructor of a ModelElement. The
     * constructor of a ModelElement uses the passed in parent ModelElement to
     * call this method on the parent ModelElement in order to add itself as a
     * child element on the parent. The modelElement's parent will be set to this
     * element's parent
     *
     * @param modelElement the model element to be added.
     */
    private fun addModelElement(modelElement: ModelElement) {
        // add the model element to the list of children
        myModelElements.add(modelElement)
        // set its parent to this element
        modelElement.myParentModelElement = this
    }

    /**
     * Gets an iterator to the contained model elements.
     *
     * @return an iterator over the child elements.
     */
    internal fun getChildModelElementIterator(): Iterator<ModelElement> {
        return myModelElements.iterator()
    }

    /**
     * Gets the number of model elements contained by this model elements.
     *
     * @return a count of the number of direct child elements.
     */
    val numberOfModelElements: Int
        get() = myModelElements.size

    /** Gets all model elements that are contained within this model element
     * in parent-child order within the hierarchy
     *
     * @param list the list to fill
     */
    protected fun getAllModelElements(list: MutableList<ModelElement>) {
        list.add(this)
        if (myModelElements.isNotEmpty()) {
            for (me in myModelElements) {
                me.getAllModelElements(list)
            }
        }
    }

    /** A list containing the (child) model elements of only this model element
     *
     * @param list the list of model elements
     */
    protected fun getThisElementsModelElements(list: MutableList<ModelElement>) {
        if (myModelElements.isNotEmpty()) {
            for (me in myModelElements) {
                list.add(me)
            }
        }
    }

    /**
     * Fills up the provided collection carrying all the response variables
     * that are contained by any model elements within this model element. In other
     * words, any response variables that are in the model element hierarchy
     * below this model element.
     *
     * @param c The collection to be filled.
     */
    protected fun getAllResponseVariables(c: MutableCollection<Response>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                m.getAllResponseVariables(c)
            }
        }

        // check if I'm a response variable, if so add me
        if (this is Response) {
            c.add(this)
        }
    }

    /**
     * Fills up the provided collection carrying the response variables that are
     * contained only by this model element
     *
     * @param c The collection to be filled.
     */
    protected fun getThisElementsResponseVariables(c: MutableCollection<Response>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                if (m is Response) {
                    c.add(m)
                }
            }
        }
    }

    /**
     * Fills up the provided collection carrying all the Counters that are
     * contained by any model elements within this model element. In other
     * words, any Counters that are in the model element hierarchy below this
     * model element.
     *
     * @param c The collection to be filled.
     */
    protected fun getAllCounters(c: MutableCollection<Counter>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                m.getAllCounters(c)
            }
        }

        // check if I'm a Counter, if so add me
        if (this is Counter) {
            c.add(this)
        }
    }

    /**
     * Fills up the provided collection carrying the Counters that are contained
     * only by this model element
     *
     * @param c The collection to be filled.
     */
    protected fun getThisElementsCounters(c: MutableCollection<Counter>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                if (m is Counter) {
                    c.add(m)
                }
            }
        }
    }

    /**
     * Fills up the provided collection carrying all the RandomElementIfc and
     * subclasses of RandomElementIfc that are contained by any model elements
     * within this model element. In other words, any RandomElementIfc that are
     * in the model element hierarchy below this model element.
     *
     * @param c The collection to be filled.
     */
    protected fun getAllRandomElements(c: MutableCollection<RandomElementIfc>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                m.getAllRandomElements(c)
            }
        }

        //	check if I'm a random variable, if so add me
        if (this is RandomElementIfc) {
            c.add(this as RandomElementIfc)
        }
    }

    /**
     * Fills up the provided collection carrying only the random variables
     * associated carrying this element
     *
     * @param c The collection to be filled.
     */
    protected fun getThisElementsRandomVariables(c: MutableCollection<RandomVariable>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                if (m is RandomVariable) {
                    c.add(m)
                }
            }
        }
    }

    /**
     * Fills up the provided collection carrying only the variables associated
     * carrying
     * this element
     *
     * @param c The collection to be filled.
     */
    protected fun getThisElementsVariables(c: MutableCollection<Variable>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                if (m is Variable) {
                    if (Variable::class == m::class) {
                        c.add(this as Variable)
                    }
                }
            }
        }
    }

    /**
     * Fills up the provided collection carrying all the variables that are
     * contained by any model elements within this model element. In other
     * words, any variables that are in the model element hierarchy below this
     * model element.
     *
     * @param c The collection to be filled.
     */
    protected fun getAllVariables(c: MutableCollection<Variable?>) {
        if (myModelElements.isNotEmpty()) { // I have elements, so check them
            for (m in myModelElements) {
                m.getAllVariables(c)
            }
        }

        //	check if I'm a variable, if so add me
        if (this is Variable) {
            if (Variable::class == this::class) {
                c.add(this)
            }
        }
    }

    /**
     * Returns the value of a 1 millisecond time interval in terms of the base
     * time unit
     *
     * @return the value of a 1 millisecond time interval in terms of the base
     * time unit
     */
    fun millisecond(): Double {
        return TimeUnit.MILLISECOND.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1-second time interval in terms of the base time
     * unit
     *
     * @return Returns the value of a 1-second time interval in terms of the
     * base time unit
     */
    fun second(): Double {
        return TimeUnit.SECOND.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1-minute time interval in terms of the base time
     * unit. For example, if the time unit is set to hours, then minute() should
     * return 0.0166 (TIME_UNIT_MINUTE/TIME_UNIT_HOUR)
     *
     *
     * Thus, if base time unit is set to hours, then 5*minute() represents 5
     * minutes (5.0/60) and 2*day() represents 2 days. Use these methods to
     * convert timeUnits to the base time unit when scheduling events or
     * defining time parameters.
     *
     * @return Returns the value of a 1-minute time interval in terms of the
     * base time unit.
     */
    fun minute(): Double {
        return TimeUnit.MINUTE.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1-hour time interval in terms of the base time
     * unit
     *
     * @return Returns the value of a 1-hour time interval in terms of the base
     * time unit
     */
    fun hour(): Double {
        return TimeUnit.HOUR.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1-day time interval in terms of the base time unit
     *
     * @return Returns the value of a 1-day time interval in terms of the base
     * time unit
     */
    fun day(): Double {
        return TimeUnit.DAY.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1-week time interval in terms of the base time
     * unit
     *
     * @return Returns the value of a 1-week time interval in terms of the base
     * time unit
     */
    fun week(): Double {
        return TimeUnit.WEEK.value / model.baseTimeUnit.value
    }

    /**
     * Checks if a warm-up event has been scheduled for this model element
     *
     * @return True means that it has been scheduled.
     */
    fun isWarmUpEventScheduled(): Boolean {
        return if (warmUpEvent == null) {
            false
        } else {
            warmUpEvent!!.isScheduled
        }
    }

    /**
     * Checks if a warm-up event is scheduled for any model element directly
     * above this model element in the hierarchy of model elements all the way
     * until the top Model.
     *
     *
     * True means that some warm up event is scheduled in the upward chain.
     * False means that no warm up event is scheduled in the upward chain.
     *
     * @return true if any warm up event is scheduled in the upward chain
     */
    fun isAnyWarmUpEventScheduled(): Boolean {
        // if this model element does not schedule the warm-up
        // check if it’s parent does, and so on, until
        // reaching the Model
        if (!isWarmUpEventScheduled()) {
            // if it has a parent check it
            if (parent != null) {
                return parent!!.isAnyWarmUpEventScheduled()
            } else {
                // only Model has no parent to check
                // stop checking
            }
        }
        // current element has warm up scheduled, return that fact
        return true
    }

    /**
     * Find the first parent that has its own warm up event this guarantees that
     * all elements below the found model element do not have their own warm-up
     * event. A model element that has its own warm up event also opts out of
     * the warm-up action. If the returned parent is the Model, then all are
     * controlled by the model (unless they opt out). Elements can opt out and
     * not have their own warm-up event. Thus, they have no warm up at all.
     *
     * Null indicates that no model element in the parent chain has a warm-up
     * event.
     *
     * @return the element or null
     */
    fun findModelElementWithWarmUpEvent(): ModelElement? {
        // if this model element does not schedule the warm-up
        // check if it’s parent does, and so on, until
        // reaching the Model
        return if (!isWarmUpEventScheduled()) {
            // doesn't have a warm-up event
            if (parent != null) {
                // check if parent exists and has a warm-up event
                parent!!.findModelElementWithWarmUpEvent()
            } else {
                // parent does not exist, and there is no warm up event
                null
            }
        } else { // has a warm-up event, return the model element
            this
        }
    }

    /**
     * This method returns the planned time for the warm-up for this model
     * element.
     *
     * @return the planned time, 0.0 means no warm-up
     */
    fun getWarmUpEventTime(): Double {
        var m: ModelElement? = this
        var time = 0.0
        while (m != null) {
            if (m.isWarmUpEventScheduled()) {
                // element has its own warm up event
                time = m.individualElementWarmUpLength
                break
            }
            // does not have its own warm up event
            if (!m.warmUpOption) {
                // and doesn't listen to a parent
                time = 0.0
                break
            }
            m = m.parent // get the parent
        }
        return time
    }

    /**
     * Checks if this model element or any model element directly above this
     * model element in the hierarchy of model elements all the way until the
     * top Model participates in the warm-up action.
     *
     * True means that this and every parent in the chain participates in the
     * warm-up action. False means this element or some parent does not
     * participate in the warm-up action
     *
     * @return true if this and every parent participates in the warm-up action
     */
    fun checkWarmUpOption(): Boolean {
        // if this model element participates in the warm-up
        // check if it’s parent does, and so on, until
        // reaching the Model
        if (warmUpOption) {
            // if it has a parent check it
            if (parent != null) {
                return parent!!.checkWarmUpOption()
            } else {
                // only Model has no parent to check
                // stop checking
            }
        }
        // current element does not participate, return that fact
        return false
    }

    /**
     * Cancels the warm-up event for this model element.
     */
    fun cancelWarmUpEvent() {
        if (warmUpEvent != null) {
            warmUpEvent!!.cancel = true
        }
    }

    /**
     * Checks if a timed update event has been scheduled for this model element
     *
     * @return True means that it has been scheduled.
     */
    fun isTimedUpdateEventScheduled(): Boolean {
        return if (timedUpdateEvent == null) {
            false
        } else {
            timedUpdateEvent!!.isScheduled
        }
    }

    /**
     * Cancels the timed update event for this model element.
     */
    fun cancelTimedUpdateEvent() {
        if (timedUpdateEvent != null) {
            timedUpdateEvent!!.cancel = true
        }
    }

    /** An interface used to implement the actions associated with
     * event logic within the simulation.
     *
     * Implementor's of this interface should define a class that has concrete
     * specification for the type T.  If the event message is not used, then
     * specify the type as Nothing.
     *
     * @param <T> the type associated with the KSLEvent's message property
     */
    fun interface EventActionIfc<T> {
        /** This must be implemented by any objects that want to supply event
         * logic.  This is essentially the "event routine".
         * @param event The event that triggered this action.
         */
        fun action(event: KSLEvent<T>)
    }

    /**
     * A convenience base class for creating event actions associated with the model element.
     * The class has the ability to schedule its action.
     */
    protected abstract inner class EventAction<T> : EventActionIfc<T> {

        /**
         * Allows event actions to more conveniently schedule themselves
         * @param timeToEvent the time to the next action
         * @param priority the priority of the action
         * @param message a general object to attach to the action
         * @param name a name to associate with the event for the action
         * @return the scheduled event
         */
        fun schedule(
            timeToEvent: Double,
            message: T? = null,
            priority: Int = KSLEvent.DEFAULT_PRIORITY,
            name: String? = null
        ): KSLEvent<T> {
            return schedule(this, timeToEvent, message, priority, name)
        }

        fun schedule(
            timeToEvent: GetValueIfc,
            message: T? = null,
            priority: Int = KSLEvent.DEFAULT_PRIORITY,
            name: String? = null
        ): KSLEvent<T> {
            return schedule(timeToEvent.value, message, priority, name)
        }
    }

    /**
     * A convenience base class for creating event actions associated with the model element.
     * The class has the ability to schedule its action according to a repeating
     * time between events.
     */
    protected abstract inner class TimedEventAction<T>(theTimeBtwEvents: GetValueIfc) : EventAction<T>() {
        protected var timeBetweenEvents: GetValueIfc = theTimeBtwEvents

        fun schedule(
            message: T? = null,
            priority: Int = KSLEvent.DEFAULT_PRIORITY,
            name: String? = null
        ): KSLEvent<T> {
            return schedule(timeBetweenEvents.value, message, priority, name)
        }
    }

    /**
     * Allows event actions to be scheduled by model elements
     * @param eventAction the event action to schedule
     * @param timeToEvent the time to the next action
     * @param priority the priority of the action
     * @param message a general object to attach to the action
     * @param name a name to associate with the event for the action
     * @return the scheduled event
     */
    protected fun <T> schedule(
        eventAction: EventActionIfc<T>,
        timeToEvent: GetValueIfc,
        message: T? = null,
        priority: Int = KSLEvent.DEFAULT_PRIORITY,
        name: String? = null
    ): KSLEvent<T> {
        return schedule(eventAction, timeToEvent.value, message, priority, name)
    }

    /**
     * Allows event actions to be scheduled by model elements
     * @param eventAction the event action to schedule
     * @param timeToEvent the time to the next action
     * @param priority the priority of the action
     * @param message a general object to attach to the action
     * @param name a name to associate with the event for the action
     * @return the scheduled event
     */
    protected fun <T> schedule(
        eventAction: EventActionIfc<T>,
        timeToEvent: Double,
        message: T? = null,
        priority: Int = KSLEvent.DEFAULT_PRIORITY,
        name: String? = null
    ): KSLEvent<T> {
        return executive.scheduleEvent(this, eventAction, timeToEvent, message, priority, name)
    }

    /**
     * Creates an EventScheduler which can be used to create and schedule events
     * on the simulation calendar reactingWith a fluency pattern.
     *
     * @param <T>    if the event has a message, this is the type
     * @param action the action to be invoked at the event time
     * @return the builder of the event
     * */
    protected fun <T> schedule(action: EventActionIfc<T>): EventBuilderIfc<T> {
        return EventScheduler(action)
    }

    protected interface EventBuilderIfc<T> {
        /**
         * An object of type T that is attached to the event
         *
         * @param message the message to attach
         * @return the builder
         */
        fun withMessage(message: T): EventBuilderIfc<T>

        /**
         * Sets the scheduling priority of the event, lower is faster
         *
         * @param priority the priority
         * @return the builder
         */
        fun havingPriority(priority: Int): EventBuilderIfc<T>

        /**
         * Sets the name of the event being built
         *
         * @param name the name of the event
         * @return the builder
         */
        fun name(name: String?): EventBuilderIfc<T>

        /**
         * Causes the event that is being built to be scheduled at the current
         * simulation time (no time offset)
         *
         * @return the event that was scheduled
         */
        fun now(): KSLEvent<T>

        /**
         * Sets the time of the event being built to current time +
         * value.getValue()
         *
         * @param value an object that can compute the time via getValue()
         * @return the builder
         */
        fun after(value: GetValueIfc): TimeUnitIfc<T> //would have liked to use the word "in"

        /**
         * Sets the time of the event being built to current time + time
         *
         * @param time the time until the event should occur
         * @return the builder
         */
        fun after(time: Double): TimeUnitIfc<T>
    }

    /**
     * Uses the builder pattern to create and schedule the event and the action
     * associated carrying the event
     *
     * @param <T> the type associated carrying the messages on the event */
    protected inner class EventScheduler<T>(action: EventActionIfc<T>) : EventBuilderIfc<T>, TimeUnitIfc<T> {
        private var time = 0.0
        private var name: String? = null
        private var message: T? = null
        private var priority: Int
        private val action: EventActionIfc<T>

        init {
            priority = KSLEvent.DEFAULT_PRIORITY
            this.action = action
        }

        override fun now(): KSLEvent<T> {
            return after(0.0).units()
        }

        override fun after(value: GetValueIfc): TimeUnitIfc<T> {
            return after(value.value)
        }

        override fun after(time: Double): TimeUnitIfc<T> {
            this.time = time
            return this
        }

        override fun name(name: String?): EventBuilderIfc<T> {
            this.name = name
            return this
        }

        override fun withMessage(message: T): EventBuilderIfc<T> {
            this.message = message
            return this
        }

        override fun havingPriority(priority: Int): EventBuilderIfc<T> {
            this.priority = priority
            return this
        }

        override fun days(): KSLEvent<T> {
            time = time * this@ModelElement.day()
            return units()
        }

        override fun minutes(): KSLEvent<T> {
            time = time * this@ModelElement.minute()
            return units()
        }

        override fun hours(): KSLEvent<T> {
            time = time * this@ModelElement.hour()
            return units()
        }

        override fun seconds(): KSLEvent<T> {
            time = time * this@ModelElement.second()
            return units()
        }

        override fun weeks(): KSLEvent<T> {
            time = time * this@ModelElement.week()
            return units()
        }

        override fun milliseconds(): KSLEvent<T> {
            time = time * this@ModelElement.millisecond()
            return units()
        }

        override fun units(): KSLEvent<T> {
            return schedule(action, time, message, priority, name)
        }
    }

    /**
     * A Tagging interface to force builder to specify time timeUnits after
     * calling the in() method.
     *
     * Converts the time within EventScheduler to timeUnits for scheduling the
     * event. Ensures that the event has the appropriate time timeUnits.
     *
     * @param <T> the type for the thing that the event might hold as a message
     * @author rossetti
     * */
    protected interface TimeUnitIfc<T> {
        /**
         * Creates and schedules the event associated carrying the model
         * interpreting the event time in days
         *
         * @return the event that was scheduled
         */
        fun days(): KSLEvent<T>

        /**
         * Creates and schedules the event associated carrying the model
         * interpreting the event time in minutes
         *
         * @return the event that was scheduled
         */
        fun minutes(): KSLEvent<T>

        /**
         * Creates and schedules the event associated carrying the model
         * interpreting the event time in hours
         *
         * @return the event that was scheduled
         */
        fun hours(): KSLEvent<T>

        /**
         * Creates and schedules the event associated carrying the model
         * interpreting the event time in seconds
         *
         * @return the event that was scheduled
         */
        fun seconds(): KSLEvent<T>

        /**
         * Creates and schedules the event associated carrying the model
         * interpreting the event time in weeks
         *
         * @return the event that was scheduled
         */
        fun weeks(): KSLEvent<T>

        /**
         * Creates and schedules the event associated carrying the model
         * interpreting the event time in milliseconds
         *
         * @return the event that was scheduled
         */
        fun milliseconds(): KSLEvent<T>

        /**
         * Creates and schedules the event reactingWith the base time timeUnits
         * associated
         * carrying the model
         *
         * @return the event that was scheduled
         */
        fun units(): KSLEvent<T>
    }

    /** Includes the model name, the id, the model element name, the parent name, and parent id
     *
     * @return a string representing the model element
     */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("ModelElement{")
        sb.append("Class Name=")
        sb.append(this::class.simpleName)
        sb.append(", Id=")
        sb.append(id)
        sb.append(", Name='")
        sb.append(name)
        sb.append('\'')
        sb.append(", Parent Name='")
        var pName: String? = null
        var pid = 0
        if (parent != null) {
            pName = myParentModelElement!!.name
            pid = myParentModelElement!!.id
        }
        sb.append(pName)
        sb.append(", Parent ID=")
        sb.append(pid)
        sb.append(", Model=")
        sb.append(model.name)
        sb.append('}')
        return sb.toString()
    }

    /**
     * This method is called from Model
     *
     * @param count the count label initializer for the root
     * @return the count to get back to this node
     */
    internal fun markPreOrderTraversalTree(count: Int): Int {
        var c = count
        c = c + 1
        leftTraversalCount = c
        for (m in myModelElements) {
            c = m.markPreOrderTraversalTree(c)
        }
        // reached end of children or no children
        c = c + 1
        rightTraversalCount = c
        return c
    }

    /**
     * This method should be overridden by subclasses that need logic to be
     * performed prior to an experiment. The beforeExperiment method allows
     * model elements to be setup prior to the first replication within an
     * experiment. It is called once before any replications occur.
     */
    protected open fun beforeExperiment() {}

    /**
     * The beforeExperimentActions method allows model elements to be setup prior to
     * the first replication within an experiment. It is called once before any
     * replications occur within the experiment. This method ensures that each
     * contained model element has its beforeExperiment method called and that
     * any observers will be notified of this action
     */
    internal fun beforeExperimentActions() {
        warmUpIndicator = false
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.beforeExperimentActions()
            }
        }

        if (beforeExperimentOption) {
            logger.trace { "ModelElement: $name executing beforeExperiment()" }
            beforeExperiment()
            currentStatus = Status.BEFORE_EXPERIMENT
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed to initialize prior to a replication. It is called once before
     * each replication occurs if the model element wants initialization. It is
     * called after beforeReplication() is called
     */
    protected open fun initialize() {}//TODO consider making this abstract so that elements have to implement

    /**
     * The initializeActions method allows model elements to be initialized to a
     * standard reactor defined state. It is called by default before each
     * replication
     *
     *
     * This method ensures that each contained model element has its initialize
     * method called and that any observers will be notified of this action
     */
    internal fun initializeActions() {
        // first initialize any children associated carrying this model element
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.initializeActions()
            }
        }

        // now initialize the model element itself
        if (initializationOption) {
            logger.trace { "ModelElement: $name executing initialize()" }
            initialize()
            currentStatus = Status.INITIALIZED
        }
    }

    /**
     * This method should be overridden by subclasses that need to register
     * conditional actions prior to a replication with the executive. It is called once before each
     * replication, right after the method initialize() is called. The user
     * can use the executive property to access the Executive
     */
    protected open fun registerConditionalActions() {}

    /**
     * The registerConditionalActionsWithExecutive() method allows model elements to be
     * register any conditional actions after initialization.
     *
     * It is called by default before each replication, right after the method initialize() is invoked
     *
     * This method ensures that each contained model element has its
     * registerConditionalActions() method called and that any observers will be
     * notified of this action
     *
     */
    internal fun registerConditionalActionsWithExecutive() {
        // first initialize any children associated carrying this model element
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.registerConditionalActionsWithExecutive()
            }
        }
        logger.trace { "ModelElement: $name executing registerConditionalActions()" }
        registerConditionalActions()
        currentStatus = Status.CONDITIONAL_ACTION_REGISTRATION
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed prior to each replication. It is called prior to each
     * replication and can be used to initialize the model element. It is called
     * before initialize() is called.
     */
    protected open fun beforeReplication() {}

    /**
     * The beforeReplicationActions method is called before each replication. This
     * method ensures that each contained model element's beforeReplication() method is called
     * before the method initialize() occurs.
     */
    internal fun beforeReplicationActions() {
        if (individualElementWarmUpLength > 0.0) {
            // the warm up period is > 0, ==> element wants a warm-up event
            myWarmUpEventAction = WarmUpEventAction()
            Model.logger.trace { "$name scheduling warm up event for time $individualElementWarmUpLength" }
            warmUpEvent = myWarmUpEventAction!!.schedule()
            warmUpOption = false // no longer depends on parent's warm up
        }
        if (timedUpdateInterval > 0.0) {
            // the timed update is > 0, ==> element wants a timed update event
            // schedule the timed update event
            myTimedUpdateActionListener = TimedUpdateEventAction()
            Model.logger.trace { "$name scheduling timed update event for time $timedUpdateInterval" }
            timedUpdateEvent = myTimedUpdateActionListener!!.schedule()
        }
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.beforeReplicationActions()
            }
        }
        if (beforeReplicationOption) {
            logger.trace { "ModelElement: $name executing beforeReplication()" }
            beforeReplication()
            currentStatus = Status.BEFORE_REPLICATION
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed after before replication. It is called after beforeReplication
     * but prior to afterReplication() and can be used to perform pure
     * monte-carlo (non-event type) simulations carrying the model element
     */
    protected open fun montecarlo() {}

    /**
     * The monteCarloActions method facilitates model elements to perform a monte
     * carlo simulation carrying no events being called. It is called by default
     * after beginReplication_() and initialize_().
     *
     *
     * This method ensures that each contained model element has its monte carlo
     * method called and that any observers will be notified of this action
     */
    internal fun monteCarloActions() {
        if (monteCarloOption) {
            logger.trace { "$name executing montecarlo()" }
            montecarlo()
            currentStatus = Status.MONTE_CARLO
        }
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.monteCarloActions()
            }
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed at the warm-up event during each replication. It is called once
     * during each replication if the model element reacts to warm up actions.
     */
    protected open fun warmUp() {}

    /**
     * The warmUp_ method is called once during each replication. This method
     * ensures that each contained model element that requires a warm-up action
     * will perform its actions.
     */
    private fun warmUpAction() {
        // if we get here the warm-up was scheduled, so do it
        if (this == model) {
            Model.logger.trace { "Executing the warm up action for the model at time $time" }
        }
        logger.trace { "ModelElement: $name executing warmUp()" }
        warmUp()
        warmUpIndicator = true
        currentStatus = Status.WARMUP
        // warm up the children that need it
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                if (m.warmUpOption) {
                    m.warmUpAction()
                }
            }
        }
    }

    private inner class WarmUpEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            warmUpAction()
        }

        fun schedule(): KSLEvent<Nothing> {
            return schedule(individualElementWarmUpLength, null, warmUpPriority, name = "${name}_WarmUp")
        }
    }

    /**
     * The update method can be called at reactor defined points to indicate
     * that the model element has been changed in some fashion that the update status
     * observers need notification. This method ensures that each contained
     * model element that requires an update action will perform its actions.
     */
    protected fun update() {// protected so that subclasses can call this at points that require updates
        logger.trace { "$name executing update()" }
        currentStatus = Status.UPDATE
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.update()
            }
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed at each timed update event during each replication. It is
     * called for each timed update during each replication if the model element
     * reacts to timed update actions.
     */
    protected open fun timedUpdate() {}

    /**
     * The timedUpdate_ method is called multiple times during each replication.
     * This method ensures that each contained model element that requires a
     * timed update action will perform its actions.
     */
    private fun timedUpdateActions() {
        if (timedUpdateOption) {
            logger.trace { "$name executing timedUpdate()" }
            timedUpdate()
            currentStatus = Status.TIMED_UPDATE
        }
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                if (!m.isTimedUpdateEventScheduled()) {
                    m.timedUpdateActions()
                }
            }
        }
    }

    private inner class TimedUpdateEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            timedUpdateActions()
            schedule()
        }

        fun schedule(): KSLEvent<Nothing> {
            return schedule(
                timedUpdateInterval, null, timedUpdatePriority,
                name = "${name}_TimedUpdate"
            )
        }

    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed when the replication ends and prior to the calling of
     * afterReplication() . It is called when each replication ends and can be
     * used to collect data from the model element, etc.
     */
    protected open fun replicationEnded() {}

    /**
     * The replicationEndedActions method is called when a replication ends This
     * method ensures that each contained model element that requires an end of
     * replication action will perform its actions.
     */
    internal fun replicationEndedActions() {
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.replicationEndedActions()
            }
        }
        if (replicationEndedOption) {
            logger.trace { "ModelElement: $name executing replicationEnded()" }
            replicationEnded()
            currentStatus = Status.REPLICATION_ENDED
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed after each replication. It is called after replicationEnded()
     * has been called.
     */
    protected open fun afterReplication() {}

    /**
     * The afterReplicationActions method is called at the end of each replication.
     * This method ensures that each contained model element that requires an end
     * of replication action will perform its actions.
     */
    internal fun afterReplicationActions() {
        if (myModelElements.isNotEmpty()) {
            logger.trace { "ModelElement: $name has children, executing their after replication actions" }
            for (m in myModelElements) {
                m.afterReplicationActions()
            }
        }
        if (afterReplicationOption) {
            logger.trace { "ModelElement: $name executing afterReplication()" }
            afterReplication()
            currentStatus = Status.AFTER_REPLICATION
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed after an experiment has been completed It is called after all
     * replications are done and can be used to collect data from the model
     * element, etc.
     */
    protected open fun afterExperiment() {}

    /**
     * The afterExperimentActions method is called after all replications are
     * completed for an experiment. This method ensures that each contained
     * model element that requires an action at the end of an experiment will
     * perform its actions.
     */
    internal fun afterExperimentActions() {
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.afterExperimentActions()
            }
        }
        if (afterExperimentOption) {
            logger.trace { "ModelElement: $name executing afterExperiment()" }
            afterExperiment()
            currentStatus = Status.AFTER_EXPERIMENT
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed when a model element is removed from a model
     */
    protected open fun removedFromModel() {}

    /**
     * Recursively removes this model element and the children of this model
     * element and all their children, etc. The children will no longer have a
     * parent and will no longer have a model.  This can only be done when
     * the simulation that contains the model is not running.
     *
     * This method has very serious side effects. After invoking this method:
     *
     * 1) All children of this model element will have been removed from the
     * model.
     * 2) This model element will be removed from its parent's model,
     * element list and from the model. The getParentModelElement() method will
     * return null. In other words, this model element will no longer be connected
     * to a parent model element.
     * 3) This model element and all its children will no longer be
     * connected. In other words, there is no longer a parent/child relationship
     * between this model element and its former children.
     * 4) This model element and all of its children will no longer belong to a model.
     * Their getModel() method will return null
     * 5) The removed elements are no longer part of their former model's model element map
     * 6) The name and label are set to null
     * 7) Warm up and timed update listeners are set to null
     * 9) Any reference to a spatial model is set to null
     * 10) All observers of this model element are detached
     * 11) All child model elements are removed. It will no longer have any children.
     *
     * Since it has been removed from the model, it and its children will no
     * longer participate in any of the standard model element actions, e.g.
     * initialize(), afterReplication(), etc.
     *
     *
     * Notes: 1) This method removes from the list of model elements. Thus, if a
     * client attempts to use this method, via code that is iterating the list a
     * concurrent modification exception will occur.
     * 2) The user is responsible for ensuring that other references to this model
     * element are correctly handled.  If references to this model element exist within
     * other data structures/collections then the user is responsible for appropriately
     * addressing those references. This is especially important for any observers
     * of the removed model element.  The observers will be notified that the model
     * element is being removed. It is up to the observer to correctly react to
     * the removal. If the observer is a subclass of ModelElementObserver then
     * implementing the removedFromModel() method can be used. If the observer is a
     * general Observer, then use REMOVED_FROM_MODEL to check if the element is being removed.
     */
    internal fun removeFromModel() {
        if (model.isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to remove the model element: ")
            sb.append(name)
            sb.append(" while the simulation was running.")
            Model.logger.error { sb.toString() }
            throw IllegalStateException(sb.toString())
        }

//		System.out.println("In " + getName() + " removeFromModel()");
        // first remove any of the model element's children

        while (myModelElements.isNotEmpty()) {
            val child = myModelElements[myModelElements.size - 1]
            child.removeFromModel()
        }
        logger.trace { "ModelElement: $name executing removeFromModel()" }
        // if the model element has a warm-up event, cancel it
        if (warmUpEvent != null) {
            if (warmUpEvent!!.isScheduled) {
                logger.trace { "ModelElement: $name cancelling warmup event" }
                warmUpEvent!!.cancel = true
            }
            warmUpEvent = null
            myWarmUpEventAction = null
        }
        // if the model element has a timed update event, cancel it
        if (timedUpdateEvent != null) {
            if (timedUpdateEvent!!.isScheduled) {
                logger.trace { "ModelElement: $name cancelling timed update event" }
                timedUpdateEvent!!.cancel = true
            }
            timedUpdateEvent = null
            myTimedUpdateActionListener = null
        }

        // allow the subclasses to provide specific removal behavior
        removedFromModel()
        // notify any model element observers of the removal
        currentStatus = Status.REMOVED_FROM_MODEL
        // need to ensure that any observers are detached
        detachAllModelElementObservers()
        // tell the parent to remove it, remove it
        parent!!.removeModelElement(this)
        // remove it from the model element map
        model.removeFromModelElementMap(this)
        // no longer has a parent
        //TODO       myParentModelElement = null
        // no longer is in the model
//TODO        myModel = null
        // can't be in a spatial model
//TODO        mySpatialModel = null
        myModelElements.clear()
//        myModelElements = null
//        name = null
        label = null
    }

    /**
     * Removes the "child" model element from this model element. The model
     * element to be removed must not be null; otherwise, an
     * IllegalArgumentException will be thrown.
     *
     * @param modelElement the model element to be removed.
     * @return True indicates that the remove was successful.
     */
    private fun removeModelElement(modelElement: ModelElement): Boolean {
        //TODO why have a 1-line method for this
        return myModelElements.remove(modelElement)
    }

    /**
     * A Comparator for comparing model elements based on getId()
     */
    class ModelElementComparator : Comparator<ModelElement> {
        override fun compare(o1: ModelElement, o2: ModelElement): Int {
            return o1.id.compareTo(o2.id)
        }
    }

    companion object {
        private var enumCounter: Int = 0

        fun nextEnumConstant() : Int {
            return ++enumCounter
        }

        /**
         * A global logger for logging of model elements
         */
        val logger = KotlinLogging.logger {}
    }

    fun attachModelElementObserver(observer: ModelElementObserver) {
        require(!isModelElementObserverAttached(observer)) { "The supplied observer is already attached" }
        modelElementObservers.add(observer)
    }

    fun detachModelElementObserver(observer: ModelElementObserver) {
        modelElementObservers.remove(observer)
    }

    fun detachAllModelElementObservers() {
        modelElementObservers.clear()
    }

    fun isModelElementObserverAttached(observer: ModelElementObserver): Boolean {
        return modelElementObservers.contains(observer)
    }

    fun countModelElementObservers(): Int {
        return modelElementObservers.size
    }

    /** Notify the observers
     *
     * @param newValue
     */
    protected fun notifyModelElementObservers(newValue: Status) {
        for (o in modelElementObservers) {
            o.onChange(this, newValue)
        }
    }

    interface QObjectIfc : NameIfc {
        /**
         * Gets a uniquely assigned identifier for this QObject. This
         * identifier is assigned when the QObject is created. It may vary if the
         * order of creation changes.
         */
        val id: Long

        /**
         * The name of the QObject
         */
        override val name: String

        /**
         *  The current simulation time. Attached to the queue object for convenience
         *  of checking time outside of model element instances.
         */
        val currentTime: Double

        /**
         * The time that the QObject was created
         */
        val createTime: Double

        /**
         *  The priority of the QObject for use by the queue
         */
        val priority: Int

        /**
         * This method can be used to get direct access to the State that represents
         * when the object was queued. This allows access to the total time in the
         * queued state as well as other statistical accumulation of state
         * statistics
         *
         * @return Returns the QueuedState.
         */
        val queuedState: StateAccessorIfc

        /**
         * The time that the QObject was LAST enqueued
         */
        val timeEnteredQueue: Double

        /**
         *  The time that the QObject LAST exited a queue
         */
        val timeExitedQueue: Double

        /**
         * The time that the QObject spent in the Queue based on the LAST time dequeued
         */
        val timeInQueue: Double

        /**
         * Checks if the QObject is currently queued
         */
        val isQueued: Boolean

        /**
         *  Indicates if the QObject is not currently queued
         */
        val isNotQueued: Boolean

        /**
         *  For use within the station package. Tracks the current receiver
         */
        val currentReceiver: QObjectReceiverIfc?

        /**
         *  For use within the station package. Tracks the current sender
         */
        val sender: QObjectSenderIfc?

        /**
         *  An object that promises to produce a value. Can be used
         *  to carry a general value with the queue object.
         */
        val valueObject: GetValueIfc?

        /**
         *  An attribute to denote the type of queue object. Useful
         *  when the only thing that distinguishes subtypes is an integer value.
         *  For example, this could be randomly assigned.
         */
        val qObjectType: Int

    }

    /**
     * QObject can be used as a base class for objects that need to be placed in
     * queues on a regular basis.  A QObject can be in one and only one Queue at a time.
     * An arbitrary object can be associated with the QObject. The user is
     * responsible for managing the type of the attached object.
     *
     * Creates an QObject with the given name and the creation time set to the
     * supplied value
     *
     * @param aName The name of the QObject
     */
    open inner class QObject(aName: String? = null) : Comparable<QObject>, QObjectIfc {
        init {
            qObjCounter++
        }

        final override val currentTime: Double
            get() = this@ModelElement.time

        /**
         * Gets a uniquely assigned identifier for this QObject. This
         * identifier is assigned when the QObject is created. It may vary if the
         * order of creation changes.
         */
        final override val id: Long = qObjCounter

        /**
         * The name of the QObject
         */
        final override val name: String = aName ?: ("ID_${id}")

        /**
         * The time that the QObject was created
         */
        final override val createTime = time

        /**
         * A state representing when the QObject was queued
         */
        private val myQueuedState: State = State(name = "{$name}_State")

        /**
         * This method can be used to get direct access to the State that represents
         * when the object was queued. This allows access to the total time in the
         * queued state as well as other statistical accumulation of state
         * statistics
         *
         * @return Returns the QueuedState.
         */
        final override val queuedState: StateAccessorIfc
            get() = myQueuedState  //provides limited access to the state information

        /**
         * Sets the priority to the supplied value If the QObject is queued, the
         * queue's changePriority() method is called (possibly causing a reordering
         * of the queue) which may cause significant reordering overhead otherwise
         * the priority is directly changed Changing this value only changes how the
         * QObjects are compared and may or may not change how they are ordered in
         * the queue, depending on the queue discipline used
         */
        final override var priority: Int = 1
            set(value) {
                field = value // always make the change
                if (isQueued) {
                    //change the priority here
                    // then just tell the queue that there was a change that needs handling
                    //myQueue.priorityChanged(this)
                    queue?.priorityChanged()//removed qObject argument which needed type info but could not supply it
                }
            }

        /**
         * The current queue that the QObject is in, null if not in a queue
         */
        var queue: Queue<*>? = null //why can't it be T: QObject
            internal set

        /**
         * A reference to an object that can be attached to the QObject when queued
         */
        var attachedObject: Any? = null

        /**
         * can be used to time stamp the qObject
         */
        var timeStamp: Double = createTime
            set(value) {
                require(value >= createTime) { "The time stamp was less than the creation time $createTime" }
                field = value
            }

        /**
         * can be used to time stamp the qObject
         */
        var stationArriveTime: Double = Double.POSITIVE_INFINITY
            internal set(value) {
                require(value >= createTime) { "The station arrive was less than the creation time $createTime" }
                field = value
            }

        /**
         *  A generic attribute to indicate a type for the QObject
         */
        override var qObjectType: Int = 1

        /**
         * Allows for a generic value to be held by the QObject
         */
        override var valueObject: GetValueIfc? = null

        /**
         *  Facilitates SAM setting with a lambda
         */
        fun valueObject(value: GetValueIfc?){
            valueObject = value
        }

        /**
         *  The receiver that last received the qObject
         */
        override var currentReceiver: QObjectReceiverIfc? = null

        /**
         *  Something that knows how to send qObjects to receivers
         */
        override var sender: QObjectSenderIfc? = null

        /**
         *  Facilitates SAM setting with a lambda
         */
        fun sender(sender: QObjectSenderIfc?){
            this.sender = sender
        }

        override fun toString(): String {
            return "ID= $id, name= $name isQueued = $isQueued"
        }

        /**
         * The time that the QObject was LAST enqueued
         */
        final override val timeEnteredQueue: Double
            get() = myQueuedState.timeStateEntered

        /**
         *  The time that the QObject LAST exited a queue
         */
        final override val timeExitedQueue: Double
            get() = myQueuedState.timeStateExited

        /**
         * The time that the QObject spent in the Queue based on the LAST time dequeued
         */
        final override val timeInQueue: Double
            get() = myQueuedState.totalTimeInState

        /**
         * Checks if the QObject is queued
         */
        final override val isQueued: Boolean
            get() = myQueuedState.isEntered

        final override val isNotQueued: Boolean
            get() = !isQueued

        /**
         * Used by Queue to indicate that the QObject has entered the queue
         *
         * @param queue the queue entered
         * @param time the time
         * @param priority the priority
         * @param obj an object to attach
         */
        internal fun <T : QObject> enterQueue(queue: Queue<T>, time: Double, priority: Int, obj: Any?) {
            check(isNotQueued) { "The QObject, $this, was already queued!" }
            myQueuedState.enter(time)
            this.queue = queue
            this.priority = priority
            attachedObject = obj
        }

        /**
         * Used by Queue to indicate that the QObject exited the queue
         *
         * @param time The time QObject exited the queue
         */
        internal fun exitQueue(time: Double) {
            check(isQueued) { "The QObject was not in a queue!" }
            myQueuedState.exit(time)
            queue = null
        }

        /**
         * Returns a negative integer, zero, or a positive integer if this object is
         * less than, equal to, or greater than the specified object.
         *
         * Throws ClassCastException if the specified object's type prevents it from
         * being compared to this object.
         *
         * Throws RuntimeException if the id's of the objects are the same, but the
         * references are not when compared with equals.
         *
         * Note: This class may have a natural ordering that is inconsistent with
         * equals.
         *
         * @param other The object to compare to
         * @return Returns a negative integer, zero, or a positive integer if this
         * object is less than, equal to, or greater than the specified object.
         */
        override operator fun compareTo(other: QObject): Int {

            // compare the priorities
            if (priority < other.priority) {
                return -1
            }
            if (priority > other.priority) {
                return 1
            }

            // priorities are equal, compare time stamps
            if (timeEnteredQueue < other.timeEnteredQueue) {
                return -1
            }
            if (timeEnteredQueue > other.timeEnteredQueue) {
                return 1
            }

            // time stamps are equal, compare ids
            if (id < other.id) // lower id, implies created earlier
            {
                return -1
            }
            if (id > other.id) {
                return 1
            }

            // if the ids are equal then the object references must be equal
            // if this is not the case there is a problem
            return if (this == other) {
                0
            } else {
                throw RuntimeException("Id's were equal, but references were not, in QObject compareTo")
            }
        }

    }
}