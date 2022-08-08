package ksl.simulation

import jsl.simulation.Simulation //TODO
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc
import mu.KotlinLogging

private var myCounter_: Int = 0

private val logger = KotlinLogging.logger {}

//TODO needs to be made abstract
open class ModelElement internal constructor(theName: String? = null) : IdentityIfc,
    ObservableIfc<ModelElement.Status> by Observable() {

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
     *  Indicates the current status of the model element for observers of ModelElement.Status
     */
    var currentStatus: Status = Status.NONE
        protected set(value) {
            previousStatus = field
            field = value
            logger.trace { "ModelElement: $name changing status from previous: $previousStatus to current: $field" }
            notifyObservers(this, field)
        }

    /**
     *  Indicates the previous status of the model element for observers of ModelElement.Status
     *  This allows the transition to be noted by observers
     */
    var previousStatus: Status = Status.NONE
        private set

    /**
     *  Checks if current status is the supplied status
     */
    fun isStatus(status: Status): Boolean {
        return status == currentStatus
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
        myCounter_ = myCounter_ + 1
    }

    override val id: Int = myCounter_

    override val name: String = makeName(theName)

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
            str
        }
    }

    override var label: String? = null
        get() {
            return if (field == null) name else field
        }

    /**
     * the left traversal count for pre-order traversal of the model element tree
     */
    var myLeftCount = 0
        protected set

    /**
     * the right traversal count for pre-order traversal of the model element tree
     */
    var myRightCount = 0
        protected set

    /**
     * A flag to control whether the model element reacts to before
     * experiment actions.
     */
    protected var myBeforeExperimentOption = true

    /**
     * Sets the before experiment option of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in setup.
     */
    fun setBeforeExperimentOptionForModelElements(flag: Boolean) {
        myBeforeExperimentOption = flag
        for (m in myModelElements) {
            m.setBeforeExperimentOptionForModelElements(flag)
        }
    }

    /**
     * A flag to control whether the model element reacts to before
     * replication actions.
     */
    protected var myBeforeReplicationOption = true

    /**
     * Sets the before replication flag of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setBeforeReplicationOptionForModelElements(flag: Boolean) {
        myBeforeReplicationOption = flag
        for (m in myModelElements) {
            m.setBeforeReplicationOptionForModelElements(flag)
        }
    }

    /**
     * A flag to control whether the model element participates in monte
     * carlo actions.
     */
    protected var myMonteCarloOption = false

    /**
     * Sets the monte carlo option flag of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setMonteCarloOptionForModelElements(flag: Boolean) {
        myMonteCarloOption = flag
        for (m in myModelElements) {
            m.setMonteCarloOptionForModelElements(flag)
        }
    }

    /**
     * A flag to control whether the model element reacts to
     * initialization actions
     */
    protected var myInitializationOption = true

    /**
     * Sets the initialization option of all model elements (children) contained
     * by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setInitializationOptionForModelElements(flag: Boolean) {
        myInitializationOption = flag
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
     * A flag to control whether the model element reacts to end
     * replication actions.
     */
    protected var myReplicationEndedOption = true

    /**
     * Sets the end replication option flag of all model elements (children)
     * contained by this model element. Determines whether or not the
     * replicationEnded() method will be called
     *
     * @param flag True means that they participate in the default action
     */
    fun setReplicationEndedOptionForModelElements(flag: Boolean) {
        myReplicationEndedOption = flag
        for (m in myModelElements) {
            m.setReplicationEndedOptionForModelElements(flag)
        }
    }

    /**
     * A flag to control whether the model element reacts to after
     * replication actions.
     */
    protected var myAfterReplicationOption = true

    /**
     * Sets the after replication flag of all model elements (children)
     * contained by this model element.
     *
     * @param flag True means that they participate in the default action
     */
    fun setAfterReplicationOptionForModelElements(flag: Boolean) {
        myAfterReplicationOption = flag
        for (m in myModelElements) {
            m.setAfterReplicationOptionForModelElements(flag)
        }
    }

    /**
     * A flag to control whether the model element reacts to after
     * experiment actions.
     */
    protected var myAfterExperimentOption = true

    /**
     * Sets the after experiment option of all model elements (children)
     * contained by this model element.
     *
     * @param option True means that they participate.
     */
    fun setAfterExperimentOptionForModelElements(option: Boolean) {
        myAfterExperimentOption = option
        for (m in myModelElements) {
            m.setAfterExperimentOptionForModelElements(option)
        }
    }

    /**
     * Specifies if this model element will be warmed up when the warmup action
     * occurs for its parent.
     */
    protected var myWarmUpOption = true

    /**
     * Sets the warm up option flag of all model elements (children) contained
     * by this model element.
     *
     * @param warmUpFlag True means that they participate in the default action
     */
    fun setWarmUpOptionForModelElements(warmUpFlag: Boolean) {
        myWarmUpOption = warmUpFlag
        for (m in myModelElements) {
            m.setWarmUpOptionForModelElements(warmUpFlag)
        }
    }

    /**
     * Specifies whether this model element participates in time update
     * event specified by its parent
     */
    protected var myTimedUpdateOption = true

    /**
     * Sets the timed update option flag of all model elements (children)
     * contained by this model element.
     *
     * @param timedUpdateOption True means that they participate in the default
     * action
     */
    fun setTimedUpdateOptionForModelElements(timedUpdateOption: Boolean) {
        myTimedUpdateOption = timedUpdateOption
        for (m in myModelElements) {
            m.setTimedUpdateOptionForModelElements(timedUpdateOption)
        }
    }

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

    constructor(parent: ModelElement, name: String?) : this(name) {
        // should not be leaking this
        // adds the model element to the parent and also set this element's parent
        parent.addModelElement(this)
        // sets this element's model to the model of its parent, everyone is in the same model
        myModel = parent.myModel
        // tells the model to add this element to the overall model element map
//TODO        myModel.addToModelElementMap(this)
    }

    /**
     * This method is called from the constructor of a ModelElement. The
     * constructor of a ModelElement uses the passed in parent ModelElement to
     * call this method on the parent ModelElement in order to add itself as a
     * child element on the parent The modelElement's parent will be set to this
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
     *  the parent of this model element
     */
    protected val parent
        get() = myParentModelElement

    /**
     * the model that contains this element
     */
    protected val model
        get() = myModel

    /**
     *  the simulation that is running the model
     */
    protected val simulation: Simulation
        get() = model.mySimulation

    /**
     *  the current replication number
     */
    protected val currentReplicationNumber: Int
        get() = simulation.currentReplicationNumber

    /**
     *  the executive that is executing the events
     */
    protected val executive: Executive
        get() = model.myExecutive


    /** Gets all model elements that are contained within this model element
     * in parent-child order within the hierarchy
     *
     * @param list the list to fill
     */
    protected fun getAllModelElements(list: MutableList<ModelElement?>) {
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
    protected fun getThisElementsModelElements(list: MutableList<ModelElement?>) {
        if (!myModelElements.isEmpty()) {
            for (me in myModelElements) {
                list.add(me)
            }
        }
    }

    /**
     *  The current simulation time
     */
    protected val time
        get() = executive.currentTime * model.baseTimeUnit.value //TODO check if I should multiply by base time unit

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
     * Returns the value of a 1 second time interval in terms of the base time
     * unit
     *
     * @return Returns the value of a 1 second time interval in terms of the
     * base time unit
     */
    fun second(): Double {
        return TimeUnit.SECOND.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1 minute time interval in terms of the base time
     * unit. For example, if the time unit is set to hours, then minute() should
     * return 0.0166 (TIME_UNIT_MINUTE/TIME_UNIT_HOUR)
     *
     *
     * Thus, if base time unit is set to hours, then 5*minute() represents 5
     * minutes (5.0/60) and 2*day() represents 2 days. Use these methods to
     * convert timeUnits to the base time unit when scheduling events or
     * defining time parameters.
     *
     * @return Returns the value of a 1 minute time interval in terms of the
     * base time unit.
     */
    fun minute(): Double {
        return TimeUnit.MINUTE.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1 hour time interval in terms of the base time
     * unit
     *
     * @return Returns the value of a 1 hour time interval in terms of the base
     * time unit
     */
    fun hour(): Double {
        return TimeUnit.HOUR.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1 day time interval in terms of the base time unit
     *
     * @return Returns the value of a 1 day time interval in terms of the base
     * time unit
     */
    fun day(): Double {
        return TimeUnit.DAY.value / model.baseTimeUnit.value
    }

    /**
     * Returns the value of a 1 week time interval in terms of the base time
     * unit
     *
     * @return Returns the value of a 1 week time interval in terms of the base
     * time unit
     */
    fun week(): Double {
        return TimeUnit.WEEK.value / model.baseTimeUnit.value
    }

    /**
     * The action listener that reacts to the warm-up event.
     */
    private var myWarmUpEventAction: WarmUpEventAction? = null

    /**
     * A reference to the warm-up event
     */
    protected var myWarmUpEvent: JSLEvent<Nothing>? = null

    /**
     * Checks if a warm-up event has been scheduled for this model element
     *
     * @return True means that it has been scheduled.
     */
    fun isWarmUpEventScheduled(): Boolean {
        return if (myWarmUpEvent == null) {
            false
        } else {
            myWarmUpEvent!!.scheduled
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
    fun isWarmUpScheduled(): Boolean {
        // if this model element doesn’t schedule the warm up
        // check if it’s parent does, and so on, until
        // reaching the Model
        if (!isWarmUpEventScheduled()) {
            // if it has a parent check it
            if (parent != null) {
                return parent!!.isWarmUpScheduled()
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
                time = m.myLengthOfWarmUp
                break
            }
            // does not have its own warm up event
            if (!m.myWarmUpOption) {
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
        // if this model element participates in the warm up
        // check if it’s parent does, and so on, until
        // reaching the Model
        if (myWarmUpOption) {
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
     * Cancels the warm up event for this model element.
     */
    fun cancelWarmUpEvent() {
        if (myWarmUpEvent != null) {
            myWarmUpEvent!!.cancelled = true
        }
    }

    /**
     * Indicates whether the warm-up action occurred sometime during the
     * simulation. False indicates that the warm-up action has not occurred
     */
    protected var myWarmUpIndicator = false

    /**
     * Specifies the priority of this model element's warm up event.
     */
    protected var myWarmUpPriority = JSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY

    /**
     * The length of time from the start of the simulation to the warm-up event.
     */
    protected var myLengthOfWarmUp = 0.0 // zero means no warm up
        set(value) {
            require(value >= 0.0) { "Warm up event time must be >= 0.0" }
            field = value
            myWarmUpOption = (field == 0.0)
        }

    /**
     * The action listener that reacts to the timed update event.
     */
    private var myTimedUpdateActionListener: TimedUpdateEventAction? = null

    /**
     * A reference to the TimedUpdate event.
     */
    protected var myTimedUpdateEvent: JSLEvent<Nothing>? = null

    /**
     * Specifies the havingPriority of this model element's timed update event.
     */
    protected var myTimedUpdatePriority = JSLEvent.DEFAULT_TIMED_EVENT_PRIORITY

    /**
     * The time interval between TimedUpdate events. The default is zero,
     * indicating no timed update
     */
    protected var myTimedUpdateInterval = 0.0
        set(value) {
            require(value > 0.0) { "Time update interval must be > 0.0" }
            field = value
        }

    /**
     * Checks if a timed update event has been scheduled for this model element
     *
     * @return True means that it has been scheduled.
     */
    fun isTimedUpdateEventScheduled(): Boolean {
        return if (myTimedUpdateEvent == null) {
            false
        } else {
            myTimedUpdateEvent!!.scheduled
        }
    }

    /**
     * Cancels the timed update event for this model element.
     */
    fun cancelTimedUpdateEvent() {
        if (myTimedUpdateEvent != null) {
            myTimedUpdateEvent!!.cancelled = true
        }
    }

    /** An interface used to implement the actions associated with
     * event logic within the simulation.
     * @param <T> the type associated with the JSLEvent's message property
     *
     * Implementor's of this interface should define a class that has concrete
     * specification for the type T.
     */
    fun interface EventActionIfc<T> {
        /** This must be implemented by any objects that want to supply event
         * logic.  This is essentially the "event routine".
         * @param event The event that triggered this action.
         */
        fun action(event: JSLEvent<T>)
    }

    //TODO consider timed event action subclass with GetValueIfc

    abstract inner class EventAction<T> : EventActionIfc<T> {

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
            priority: Int = JSLEvent.DEFAULT_PRIORITY,
            message: T? = null,
            name: String? = null
        ): JSLEvent<T> {
            return schedule(this, timeToEvent, priority, message, name)
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
        timeToEvent: Double,
        priority: Int = JSLEvent.DEFAULT_PRIORITY,
        message: T? = null,
        name: String? = null
    ): JSLEvent<T> {
        return executive.scheduleEvent(this, eventAction, timeToEvent, priority, message, name)
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
        myLeftCount = c
        for (m in myModelElements) {
            c = m.markPreOrderTraversalTree(c)
        }
        // reached end of children or no children
        c = c + 1
        myRightCount = c
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
     * The beforeExperiment_ method allows model elements to be setup prior to
     * the first replication within an experiment. It is called once before any
     * replications occur within the experiment. This method ensures that each
     * contained model element has its beforeExperiment method called and that
     * any observers will be notified of this action
     */
    private fun beforeExperimentActions() {
        myWarmUpIndicator = false
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.beforeExperimentActions()
            }
        }

        if (myBeforeExperimentOption) {
            logger.info { "ModelElement: $name executing beforeExperiment()" }
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
     * The initialize_ method allows model elements to be initialized to a
     * standard reactor defined state. It is called by default before each
     * replication
     *
     *
     * This method ensures that each contained model element has its initialize
     * method called and that any observers will be notified of this action
     */
    private fun initializeActions() {
        // first initialize any children associated carrying this model element
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.initializeActions()
            }
        }

        // now initialize the model element itself
        if (myInitializationOption) {
            logger.info { "ModelElement: $name executing initialize()" }
            initialize()
            currentStatus = Status.INITIALIZED
        }
    }

    /**
     * This method should be overridden by subclasses that need to register
     * conditional actions prior to a replication with the executive. It is called once before each
     * replication, right after the initialize() method is called. The user
     * can use the executive property to access the Executive
     */
    protected open fun registerConditionalActions() {}

    /**
     * The registerConditionalActionsWithExecutive() method allows model elements to be
     * register any conditional actions after initialization.
     *
     * It is called by default before each replication, right after the initialize() method is invoked
     *
     * This method ensures that each contained model element has its
     * registerConditionalActions() method called and that any observers will be
     * notified of this action
     *
     */
    private fun registerConditionalActionsWithExecutive() {
        // first initialize any children associated carrying this model element
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.registerConditionalActionsWithExecutive()
            }
        }
        logger.info { "ModelElement: $name executing registerConditionalActions()" }
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
     * before the initialize() method occurs.
     */
    protected fun beforeReplicationActions() {
        if (myLengthOfWarmUp > 0.0) {
            // the warm up period is > 0, ==> element wants a warm-up event
            myWarmUpEventAction = WarmUpEventAction()
            myWarmUpEvent = myWarmUpEventAction!!.schedule()
            myWarmUpOption = false // no longer depends on parent's warm up
        }
        if (myTimedUpdateInterval > 0.0) {
            // the timed update is > 0, ==> element wants a timed update event
            // schedule the timed update event
            myTimedUpdateActionListener = TimedUpdateEventAction()
            myTimedUpdateEvent = myTimedUpdateActionListener!!.schedule()
        }
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.beforeReplicationActions()
            }
        }
        if (myBeforeReplicationOption) {
            logger.info { "ModelElement: $name executing beforeReplication()" }
            beforeReplication()
            currentStatus = Status.BEFORE_REPLICATION
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed after before replication. It is called after beforeReplication
     * but prior to afterReplication() and can be used to perform pure
     * monte-carlo (non event type) simulations carrying the model element
     */
    protected open fun montecarlo() {}

    /**
     * The monte carlo_ method facilitates model elements to perform a monte
     * carlo simulation carrying no events being called. It is called by default
     * after beginReplication_() and initialize_().
     *
     *
     * This method ensures that each contained model element has its monte carlo
     * method called and that any observers will be notified of this action
     */
    private fun monteCarloActions() {
        if (myMonteCarloOption) {
            logger.info { "ModelElement: $name executing montecarlo()" }
            montecarlo()
            currentStatus = Status.MONTE_CARLO
        }
        if (!myModelElements.isEmpty()) {
            for (m in myModelElements) {
                m.monteCarloActions()
            }
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed at the warm up event during each replication. It is called once
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
        logger.info { "ModelElement: $name executing warmUp()" }
        warmUp()
        myWarmUpIndicator = true
        currentStatus = Status.WARMUP
        // warm up the children that need it
        if (!myModelElements.isEmpty()) {
            for (m in myModelElements) {
                if (m.myWarmUpOption) {
                    m.warmUpAction()
                }
            }
        }
    }

    private inner class WarmUpEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            warmUpAction()
        }

        fun schedule(): JSLEvent<Nothing> {
            return schedule(myLengthOfWarmUp, myWarmUpPriority, name = this@ModelElement.name + "_WarmUp")
        }
    }

    /**
     * The update method can be called at reactor defined points to indicate
     * that the model element has been changed in some fashion that the update status
     * observers need notification. This method ensures that each contained
     * model element that requires an update action will perform its actions.
     */
    protected fun update() {
        logger.info { "ModelElement: $name executing update()" }
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
     * timed update action will performs its actions.
     */
    private fun timedUpdateActions() {
        if (myTimedUpdateOption) {
            logger.info { "ModelElement: $name executing timedUpdate()" }
            timedUpdate()
            currentStatus = Status.TIMED_UPDATE
        }
        if (!myModelElements.isEmpty()) {
            for (m in myModelElements) {
                if (!m.isTimedUpdateEventScheduled()) {
                    m.timedUpdateActions()
                }
            }
        }
    }

    private inner class TimedUpdateEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            timedUpdateActions()
            schedule()
        }

        fun schedule(): JSLEvent<Nothing> {
            return schedule(
                myTimedUpdateInterval,
                myTimedUpdatePriority,
                name = this@ModelElement.name + "_TimedUpdate"
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
     * The replicationEnded_ method is called when a replication ends This
     * method ensures that each contained model element that requires a end of
     * replication action will performs its actions.
     */
    private fun replicationEndedActions() {
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.replicationEndedActions()
            }
        }
        if (myReplicationEndedOption) {
            logger.info { "ModelElement: $name executing replicationEnded()" }
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
     * The afterReplication_ method is called at the end of each replication.
     * This method ensures that each contained model element that requires a end
     * of replication action will performs its actions.
     */
    private fun afterReplicationActions() {
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.afterReplicationActions()
            }
        }
        if (myAfterReplicationOption) {
            logger.info { "ModelElement: $name executing afterReplication()" }
            afterReplication()
            currentStatus = Status.AFTER_REPLICATION
        }
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed after an experiment has been completed It is called after all
     * replications are done and can be used to collect data from the the model
     * element, etc.
     */
    protected open fun afterExperiment() {}

    /**
     * The afterExperiment_ method is called after all replications are
     * completed for an experiment. This method ensures that each contained
     * model element that requires an action at the end of an experiment will
     * perform its actions.
     */
    private fun afterExperimentActions() {
        if (myModelElements.isNotEmpty()) {
            for (m in myModelElements) {
                m.afterExperimentActions()
            }
        }
        if (myAfterExperimentOption) {
            logger.info { "ModelElement: $name executing afterExperiment()" }
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
     * This method has very serious side-effects. After invoking this method:
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
     * the removal. If the observer is a sub-class of ModelElementObserver then
     * implementing the removedFromModel() method can be used. If the observer is a
     * general Observer, then use REMOVED_FROM_MODEL to check if the element is being removed.
     */
    fun removeFromModel() {
        if (simulation.isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to remove the model element: ")
            sb.append(name)
            sb.append(" while the simulation was running.")
            Simulation.LOGGER.error(sb.toString()) //TODO, try logging and an exception option
            throw IllegalStateException(sb.toString())
        }

//		System.out.println("In " + getName() + " removeFromModel()");
        // first remove any of the model element's children
        while (myModelElements.isNotEmpty()) {
            val child = myModelElements[myModelElements.size - 1]
            child.removeFromModel()
        }
        logger.info { "ModelElement: $name executing removeFromModel()" }
        // if the model element has a warm-up event, cancel it
        if (myWarmUpEvent != null) {
            if (myWarmUpEvent!!.scheduled) {
                logger.info { "ModelElement: $name cancelling warmup event" }
                myWarmUpEvent!!.cancelled = true
            }
            myWarmUpEvent = null
            myWarmUpEventAction = null
        }
        // if the model element has a timed update event, cancel it
        if (myTimedUpdateEvent != null) {
            if (myTimedUpdateEvent!!.scheduled) {
                logger.info { "ModelElement: $name cancelling timed update event" }
                myTimedUpdateEvent!!.cancelled = true
            }
            myTimedUpdateEvent = null
            myTimedUpdateActionListener = null
        }

        // allow the subclasses to provide specific removal behavior
        removedFromModel()
        // notify any model element observers of the removal
        currentStatus = Status.REMOVED_FROM_MODEL
        // need to ensure that any observers are detached
        detachAllObservers()
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
        /**
         * Used to assign unique enum constants
         */
        private var myEnumCounter_ = 0

        val nextEnumConstant: Int
            get() = ++myEnumCounter_

        /**
         *
         * @return a comparator that compares based on getId()
         */
        val modelElementComparator: Comparator<ModelElement>
            get() = ModelElementComparator()
    }

}