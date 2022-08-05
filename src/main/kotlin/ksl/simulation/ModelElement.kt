package ksl.simulation

import jsl.simulation.Simulation
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc

private var myCounter_: Int = 0

//TODO needs to be made abstract
open class ModelElement internal constructor(theName: String? = null) : IdentityIfc,
    ObservableIfc<ModelElement> by Observable() {

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
     * A flag to control whether the model element reacts to before
     * replication actions.
     */
    protected var myBeforeReplicationOption = true

    /**
     * A flag to control whether the model element participates in monte
     * carlo actions.
     */
    protected var myMonteCarloOption = false

    /**
     * A flag to control whether the model element reacts to
     * initialization actions
     */
    protected var myInitializationOption = true

    /**
     * A flag to control whether the model element reacts to end
     * replication actions.
     */
    protected var myReplicationEndedOption = true

    /**
     * A flag to control whether the model element reacts to after
     * replication actions.
     */
    protected var myAfterReplicationOption = true

    /**
     * A flag to control whether the model element reacts to after
     * experiment actions.
     */
    protected var myAfterExperimentOption = true

    /**
     * Specifies if this model element will be warmed up when the warmup action
     * occurs for its parent.
     */
    protected var myWarmUpOption = true

    /**
     * Specifies whether this model element participates in time update
     * event specified by its parent
     */
    protected var myTimedUpdateOption = true

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
     *  the executive that is executing the events
     */
    protected val executive: Executive
        get() = model.myExecutive

    /**
     *  The current simulation time
     */
    protected val time
        get() = executive.currentTime

    /**
     * The action listener that reacts to the warm-up event.
     */
    private var myWarmUpActionListener: WarmUpEventAction? = null

    /**
     * A reference to the warm-up event
     */
    protected var myWarmUpEvent: JSLEvent<Nothing>? = null

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
    protected var myLengthOfWarmUp = 0.0 // zero is no warm up

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
     * This method should be overridden by subclasses that need actions
     * performed to initialize prior to a replication. It is called once before
     * each replication occurs if the model element wants initialization. It is
     * called after beforeReplication() is called
     */
    protected open fun initialize() {}

    /**
     * This method should be overridden by subclasses that need to register
     * conditional actions prior to a replication. It is called once before each
     * replication, right after the initialize() method is called.
     *
     * @param e provides access to the executive
     */
    protected open fun registerConditionalActions(e: jsl.simulation.Executive?) {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed prior to each replication. It is called prior to each
     * replication and can be used to initialize the model element. It is called
     * before initialize() is called.
     */
    protected open fun beforeReplication() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed after before replication. It is called after beforeReplication
     * but prior to afterReplication() and can be used to perform pure
     * monte-carlo (non event type) simulations carrying the model element
     */
    protected open fun montecarlo() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed at the warm up event during each replication. It is called once
     * during each replication if the model element reacts to warm up actions.
     */
    protected open fun warmUp() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed at each timed update event during each replication. It is
     * called for each timed update during each replication if the model element
     * reacts to timed update actions.
     */
    protected open fun timedUpdate() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed when the replication ends and prior to the calling of
     * afterReplication() . It is called when each replication ends and can be
     * used to collect data from the the model element, etc.
     */
    protected open fun replicationEnded() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed after each replication. It is called after replicationEnded()
     * has been called.
     */
    protected open fun afterReplication() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed after an experiment has been completed It is called after all
     * replications are done and can be used to collect data from the the model
     * element, etc.
     */
    protected open fun afterExperiment() {}

    /**
     * This method should be overridden by subclasses that need actions
     * performed when a model element is removed from a model
     */
    protected open fun removedFromModel() {}

    /**
     * The warmUp_ method is called once during each replication. This method
     * ensures that each contained model element that requires a warm-up action
     * will perform its actions.
     */
    private fun warmUpAction() {
        // if we get here the warm-up was scheduled, so do it
        warmUp()
        myWarmUpIndicator = true
//TODO        notifyWarmUpObservers()
        // warm up the children that need it
        if (!myModelElements.isEmpty()) {
            for (m in myModelElements) {
                if (m.myWarmUpOption == true) {
                    m.warmUpAction()
                }
            }
        }
    }

    private inner class WarmUpEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            warmUpAction()
        }
    }

    /**
     * The timedUpdate_ method is called multiple times during each replication.
     * This method ensures that each contained model element that requires a
     * timed update action will performs its actions.
     */
    protected fun timedUpdate_() {
        if (myTimedUpdateOption) {
            timedUpdate()
//TODO            notifyTimedUpdateObservers()
        }
        if (!myModelElements.isEmpty()) {
            for (m in myModelElements) {
                if (!m.isTimedUpdateEventScheduled()) {
                    m.timedUpdate_()
                }
            }
        }
    }

    private inner class TimedUpdateEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            timedUpdate_()
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

}