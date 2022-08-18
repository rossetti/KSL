package ksl.simulation

import jsl.utilities.random.rvariable.RVParameterSetter//TODO
import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.controls.Controls
import ksl.modeling.elements.RandomElementIfc
import ksl.modeling.entity.EntityType
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.Variable

class Model internal constructor(
    theSimulation: Simulation,
    eventCalendar: CalendarIfc = PriorityQueueEventCalendar(),
    name: String = theSimulation.name + "_Model"
) : ModelElement(name) {

    internal val mySimulation: Simulation = theSimulation
    internal val myExecutive: Executive = Executive(eventCalendar)

    var baseTimeUnit: TimeUnit = TimeUnit.MILLISECOND

    private var myResponseVariables: MutableList<Response> = ArrayList()

    /**
     * A list of all the Counters within the model
     */
    private var myCounters: MutableList<Counter> = ArrayList()

    /**
     * A list of all the Variables within the model
     */
    private var myVariables: MutableList<Variable> = ArrayList()

    /**
     * A list of all random elements within the model
     */
    private var myRandomElements: MutableList<RandomElementIfc> = ArrayList()

    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: MutableMap<String, ModelElement> = LinkedHashMap()

    /** to hold the controls if used
     *
     */
    private lateinit var myControls: Controls

    /**
     * to hold the parameters of the random variables if used
     */
    private val myRVParameterSetter: RVParameterSetter? = null

    internal lateinit var myDefaultEntityType: EntityType

    init {
        myModel = this
        myParentModelElement = null
        addToModelElementMap(this)
        addDefaultElements()
    }

    //TODO revisit myDefaultEntityType when working on process modeling
    private fun addDefaultElements() {
        myDefaultEntityType = EntityType(this, "DEFAULT_ENTITY_TYPE")
    }

    override fun removedFromModel() {
        //TODO think of better exception name or try to prevent this some other way
        throw IllegalStateException("The model cannot remove itself from itself!")
 //       super.removedFromModel()
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * turn on their antithetic generating streams.
     */
    fun turnOnAntithetic() {
        for (rv in myRandomElements) {
            rv.antithetic = true
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * turn off their antithetic generating streams.
     */
    fun turnOffAntithetic() {
        for (rv in myRandomElements) {
            rv.antithetic = false
        }
    }

    /**
     * Advances the streams of all RandomElementIfc n times. If n &lt;= 0, no
     * advancing occurs
     *
     * @param n the number of times to advance
     */
    fun advanceSubStreams(n: Int) {
        if (n <= 0) {
            return
        }
        for (i in 1..n) {
            advanceToNextSubStream()
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * advance their random number streams to the next sub-stream in their
     * stream.
     */
    fun advanceToNextSubStream() {
        for (rv in myRandomElements) {
            rv.advanceToNextSubStream()
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * reset their random number streams to the beginning of their starting
     * stream.
     */
    fun resetStartStream() {
        for (rv in myRandomElements) {
            rv.resetStartStream()
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * reset their random number streams to the beginning of their current sub
     * stream.
     */
    fun resetStartSubStream() {
        for (rv in myRandomElements) {
            rv.resetStartSubStream()
        }
    }

    /**
     *
     * @return the controls for the model
     */
    fun getControls(): Controls {
        if (!::myControls.isInitialized) {
            myControls = Controls(this)
        }
        return myControls
    }

    /**
     * Removes the given model element from the Model's model element map. Any
     * child model elements of the supplied model element are also removed from
     * the map, until all elements below the given model element are removed.
     *
     * @param modelElement
     */
    internal fun removeFromModelElementMap(modelElement: ModelElement) {

        if (myModelElementMap.containsKey(modelElement.name)) {
            //	remove the associated model element from the map, if there
            myModelElementMap.remove(modelElement.name)
            if (modelElement is Response) {
                myResponseVariables.remove(modelElement)
            }
            if (modelElement is Counter) {
                myCounters.remove(modelElement)
            }
            if (modelElement is RandomElementIfc) {
                myRandomElements.remove(modelElement as RandomElementIfc)
            }
            if (modelElement is Variable) {
                if (Variable::class == modelElement::class) {//TODO not 100% sure if only super type is removed
                    myVariables.remove(modelElement)
                }
            }
            modelElement.currentStatus = Status.MODEL_ELEMENT_REMOVED
            logger.trace { "Model: Removed model element ${modelElement.name} from the model with parent ${modelElement.myParentModelElement?.name}" }
            // remove any of the modelElement's children and so forth from the map
            val i: Iterator<ModelElement> = modelElement.getChildModelElementIterator()
            var m: ModelElement
            while (i.hasNext()) {
                m = i.next()
                removeFromModelElementMap(m)
            }
        }
    }

    internal fun addToModelElementMap(modelElement: ModelElement) {

        if (mySimulation.isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to add the model element: ")
            sb.append(modelElement.name)
            sb.append(" while the simulation was running.")
            Simulation.logger.error { sb.toString() }
            throw IllegalStateException(sb.toString())
        }

        if (myModelElementMap.containsKey(modelElement.name)) {
            val sb = StringBuilder()
            sb.append("A ModelElement with the name: ")
            sb.append(modelElement.name)
            sb.append(" has already been added to the Model.")
            sb.appendLine()
            sb.append("Every model element must have a unique name!")
            Simulation.logger.error(sb.toString())
            throw IllegalArgumentException(sb.toString())
        }

        myModelElementMap[modelElement.name] = modelElement
        logger.trace { "Model: Added model element ${modelElement.name} to the model with parent ${modelElement.myParentModelElement?.name}" }

        if (modelElement is Response) {
            myResponseVariables.add(modelElement)
        }

        if (modelElement is Counter) {
            myCounters.add(modelElement)
        }

        if (modelElement is RandomElementIfc) {
            myRandomElements.add(modelElement as RandomElementIfc)
        }

        if (modelElement is Variable) {
            if (Variable::class == modelElement::class) {//TODO not 100% sure if only super type is removed
                myVariables.add(modelElement)
            }
        }

        modelElement.currentStatus = Status.MODEL_ELEMENT_ADDED
    }

    /**
     * Checks to see if the model element has been registered with the Model
     * using it's uniquely assigned name.
     *
     * @param modelElementName the name of the model element to check
     * @return true if contained
     */
    fun containsModelElement(modelElementName: String): Boolean {
        return myModelElementMap.containsKey(modelElementName)
    }

    /**
     * Returns the model element associated with the name. In some sense, this
     * model violates encapsulation for ModelElements, but since the client
     * knows the unique name of the ModelElement, we assume that the client
     * knows what they are doing.
     *
     * @param name The name of the model element, the name must not be null
     * @return The named ModelElement if it exists in the model by the supplied
     * name, null otherwise
     */
    fun getModelElement(name: String): ModelElement? {
        return if (!myModelElementMap.containsKey(name)) {
            null
        } else myModelElementMap[name]
    }

    /**
     * Returns the model element that has the provided unique id or null if not
     * found
     *
     * @param id the identifier getId() of the desired ModelElement
     * @return the ModelElement
     */
    fun getModelElement(id: Int): ModelElement? {
        for (entry in myModelElementMap.entries.iterator()) {
            if (entry.value.id == id) {
                return entry.value
            }
        }
        return null
    }

    /**
     * Schedules the ending of the executive at the provided time
     *
     * @param time the time of the ending event, must be &gt; 0
     */
    internal fun scheduleEndOfReplicationEvent(time: Double) {
        require(time > 0.0) { "The time must be > 0.0" }
        if (executive.isEndEventScheduled()) {
            Simulation.logger.info { "Model: Already scheduled end of replication event for time = ${executive.endEvent!!.time} is being cancelled" }
            // already scheduled end event, cancel it
            executive.endEvent!!.cancelled = true
        }
        // schedule the new time
        if (time.isFinite()) {
            Simulation.logger.info { "Model: Scheduling end of replication at time: $time" }
            executive.endEvent = EndEventAction().schedule(time)
        } else {
            Simulation.logger.info { "Model: Did not schedule end of replication event because time was $time" }
        }

    }

    private inner class EndEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            executive.stop("Executive: Scheduled end event occurred at time $time")
        }

        fun schedule(time: Double): JSLEvent<Nothing> {
            return schedule(time, null, JSLEvent.DEFAULT_END_REPLICATION_EVENT_PRIORITY, name = "End Replication")
        }
    }

    /** Counts the number of pre-order traversals of the model element tree and
     * labels each model element with the appropriate left and right traversal
     * count.  Called from Simulation in ReplicationExecutionProcess.initializeIterations()
     *
     * @return the number of traversals in the model element hierarchy
     */
    private fun markPreOrderTraversalModelElementHierarchy() {
        markPreOrderTraversalTree(0)
    }

    private fun setUpReplication() {

        // remove any marked model elements were added during previous replication
//        removeMarkedModelElements();

        // setup warm up period
        lengthOfWarmUp = simulation.lengthOfWarmUp

        // control streams for antithetic option
        handleAntitheticReplications()

        // do all model element beforeReplication() actions
        Simulation.logger.info { "Model: executing before replication actions for model elements"}
        beforeReplicationActions()

        // schedule the end of the replication
        scheduleEndOfReplicationEvent(simulation.lengthOfReplication)

        // if necessary, initialize the model elements
        if (simulation.replicationInitializationOption) {
            // initialize the model and all model elements with initialize option on
            Simulation.logger.info { "Model: executing initialize() actions for model elements"}
            initializeActions()
        }

        // allow model elements to register conditional actions
        Simulation.logger.info { "Model: registering conditional actions for model elements"}
        registerConditionalActionsWithExecutive()

        // if monte carlo option is on, call the model element's monteCarlo() methods
        if (monteCarloOption) {
            // since monte carlo option was turned on, assume everyone wants to listen
            setMonteCarloOptionForModelElements(true)
            Simulation.logger.info { "Model: executing monteCarloActions() actions for model elements"}
            monteCarloActions()
        }
    }

    private fun handleAntitheticReplications() {
        // handle antithetic replications
        if (simulation.antitheticOption) {
            Simulation.logger.info { "Model: executing handleAntitheticReplications() setup"}
            if (currentReplicationNumber % 2 == 0) {
                // even number replication
                // return to beginning of sub-stream
                resetStartSubStream()
                // turn on antithetic sampling
                turnOnAntithetic()
            } else  // odd number replication
                if (currentReplicationNumber > 1) {
                    // turn off antithetic sampling
                    turnOffAntithetic()
                    // advance to next sub-stream
                    advanceToNextSubStream()
                }
        }
    }

    /**
     * Sets the reset start stream option for all RandomElementIfc in the model
     * to the supplied value, true is the default behavior. This method is used
     * by an experiment prior to beforeExperimentActions() being called Thus, any
     * RandomElementIfc must already have been created and attached to the model
     * elements
     *
     * @param option The option, true means to reset prior to each experiment
     */
    private fun setAllRVResetStartStreamOptions(option: Boolean) {
        for (rv in myRandomElements) {
            rv.resetStartStreamOption = option
        }
    }

    /**
     * Sets the reset next sub stream option for all RandomElementIfc in the
     * model to the supplied value, true is the default behavior. True implies
     * that the sub-streams will be advanced at the end of the replication. This
     * method is used by an experiment prior to beforeExperimentActions() being called
     * Thus, any RandomElementIfc must already have been created and attached to
     * the model elements
     *
     * @param option The option, true means to reset prior to each replication
     */
    private fun setAllRVResetNextSubStreamOptions(option: Boolean) {
        for (rv in myRandomElements) {
            rv.resetNextSubStreamOption = option
        }
    }

    //called from simulation, so internal
    internal fun setUpExperiment() {
        Simulation.logger.info { "Model: Setting up experiment ${simulation.experimentName} for simulation ${simulation.name}"}
        executive.initializeCalendar()
        Simulation.logger.info { "Model: The executive was initialized prior to any experiments. Current time = $time"}
        executive.terminationWarningMsgOption = false
        markPreOrderTraversalModelElementHierarchy()
        // already should have reference to simulation
        advanceSubStreams(simulation.numberOfStreamAdvancesPriorToRunning)

        if (simulation.antitheticOption) {
            // make sure the streams are not reset after all replications are run
            setAllRVResetStartStreamOptions(false)
            // make sure that streams are not advanced to next substreams after each replication
            // antithetic option will control this (every other replication)
            setAllRVResetNextSubStreamOptions(false)
        } else {
            // tell the model to use the specifications from the experiment
            setAllRVResetStartStreamOptions(simulation.resetStartStreamOption)
            setAllRVResetNextSubStreamOptions(simulation.advanceNextSubStreamOption)
        }

        //TODO need to apply generic control types here someday

        if (simulation.hasControls()) {
            val cMap: Map<String, Double>? = simulation.getControls()
            if (cMap != null) {
                // extract controls and apply them
                val k: Int = getControls().setControlsAsDoubles(cMap)
                Simulation.logger.info(
                    "{} out of {} controls were applied to Model {} to setup the experiment.", k, cMap.size, name
                )
            }
        }

        // if the user has asked for the parameters, then they may have changed
        // thus apply the possibly new parameters to set up the model
//TODO  after converting RVParameterSetter to kotlin code
//        if (myRVParameterSetter != null) {
//            myRVParameterSetter.applyParameterChanges(this)
//        }

        // do all model element beforeExperiment() actions
        beforeExperimentActions()
    }

    internal fun runReplication() {
        if (mySimulation.maximumAllowedExecutionTimePerReplication > 0) {
            executive.maximumAllowedExecutionTime = mySimulation.maximumAllowedExecutionTimePerReplication
        }
        Simulation.logger.info { "Model: Initializing the executive"}
        executive.initialize()
        Simulation.logger.info { "Model: The executive was initialized prior to the replication. Current time = $time"}
        Simulation.logger.info { "Model: setting up the replications for model elements"}
        setUpReplication()
        Simulation.logger.info { "Model: executing the events"}
        executive.executeAllEvents()
        Simulation.logger.info { "Model: The executive finished executing events. Current time = $time"}
        Simulation.logger.info { "Model: performing end of replication actions for model elements"}
        replicationEndedActions()
        Simulation.logger.info { "Model: performing after replication actions for model elements"}
        afterReplicationActions()
    }

    internal fun endExperiment() {
        Simulation.logger.info { "Model: performing after experiment actions for model elements"}
        afterExperimentActions()
    }
}

