package ksl.simulation

import jsl.controls.Controls //TODO replace with kotlin version
import jsl.utilities.random.rvariable.RVParameterSetter //TODO replace with kotlin version
import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar

class Model internal constructor(
    theSimulation: Simulation,
    eventCalendar: CalendarIfc = PriorityQueueEventCalendar(),
    name: String = theSimulation.name + "_Model"
) : ModelElement(name) {

    internal val mySimulation: Simulation = theSimulation
    internal val myExecutive: Executive = Executive(eventCalendar)

    var baseTimeUnit: TimeUnit = TimeUnit.MILLISECOND

    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: MutableMap<String, ModelElement> = LinkedHashMap()

    /** to hold the controls if used
     *
     */
    private var myControls: Controls? = null

    /**
     * to hold the parameters of the random variables if used
     */
    private val myRVParameterSetter: RVParameterSetter? = null

    init {
        myModel = this
        myParentModelElement = null
        addToModelElementMap(this)
        addDefaultElements()
    }

    //TODO getControls()
    /**
     *
     * @return the controls for the model
     */
//    fun getControls(): Controls {
//        if (myControls == null) {
//            myControls = Controls(this)
//        }
//        return myControls
//    }

    private fun addDefaultElements() {
       //TODO  myDefaultEntityType = EntityType(this, "DEFAULT_ENTITY_TYPE")
        println("Need to add default elements in Model!")
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
            //TODO revisit when containers are added
//            if (modelElement is ResponseVariable) {
//                myResponseVariables.remove(modelElement as ResponseVariable)
//            }
//            if (modelElement is Counter) {
//                myCounters.remove(modelElement as Counter)
//            }
//            if (modelElement is RandomElementIfc) {
//                myRandomElements.remove(modelElement as RandomElementIfc)
//            }
//            if (modelElement is Variable) {
//                if (Variable::class.java == modelElement.javaClass) {
//                    myVariables.remove(modelElement as Variable)
//                }
//            }
            modelElement.currentStatus = Status.MODEL_ELEMENT_REMOVED

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

        if (simulation.isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to add the model element: ")
            sb.append(modelElement.name)
            sb.append(" while the simulation was running.")
            Simulation.logger.error{sb.toString()}
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

        //TODO need to add the containers for these

//        if (modelElement is ResponseVariable) {
//            myResponseVariables.add(modelElement as ResponseVariable)
//        }
//
//        if (modelElement is Counter) {
//            myCounters.add(modelElement as Counter)
//        }
//
//        if (modelElement is RandomElementIfc) {
//            myRandomElements.add(modelElement as RandomElementIfc)
//        }
//
//        if (modelElement is Variable) {
//            if (Variable::class.java == modelElement.javaClass) {
//                myVariables.add(modelElement as Variable)
//            }
//        }

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
        for (entry in myModelElementMap.entries.iterator()){
            if (entry.value.id == id){
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
        if (time.isFinite()){
            Simulation.logger.info { "Model: Scheduling end of replication at time: $time" }
            executive.endEvent = EndEventAction().schedule(time)
        } else {
            Simulation.logger.info { "Model: Did not schedule end of replication event because time was $time"}
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
        myLengthOfWarmUp = simulation.lengthOfWarmUp

        // control streams for antithetic option
        handleAntitheticReplications()

        // do all model element beforeReplication() actions
        beforeReplicationActions()

        // schedule the end of the replication
        scheduleEndOfReplicationEvent(simulation.lengthOfReplication)

        // if necessary, initialize the model elements
        if (simulation.replicationInitializationOption) {
            // initialize the model and all model elements with initialize option on
            initializeActions()
        }

        // allow model elements to register conditional actions
        registerConditionalActionsWithExecutive()

        // if monte carlo option is on, call the model element's monteCarlo() methods
        if (myMonteCarloOption) {
            // since monte carlo option was turned on, assume everyone wants to listen
            setMonteCarloOptionForModelElements(true)
            monteCarloActions()
        }
    }

    private fun handleAntitheticReplications() {
        // handle antithetic replications
        if (simulation.antitheticOption) {
            if (currentReplicationNumber % 2 == 0) {
                // even number replication
                // return to beginning of sub-stream
//TODO                resetStartSubStream()
                // turn on antithetic sampling
//TODO                turnOnAntithetic()
            } else  // odd number replication
                if (currentReplicationNumber > 1) {
                    // turn off antithetic sampling
//TODO                    turnOffAntithetic()
                    // advance to next sub-stream
//TODO                    advanceToNextSubstream()
                }
        }
    }

    //called from simulation, so internal
    internal fun setUpExperiment() {
        executive.terminationWarningMsgOption = false
        markPreOrderTraversalModelElementHierarchy()
        // already should have reference to simulation
//TODO        advanceSubstreams(simulation.numberOfStreamAdvancesPriorToRunning)

        if (simulation.antitheticOption) {
            // make sure the streams are not reset after all replications are run
//TODO            setAllRVResetStartStreamOptions(false)
            // make sure that streams are not advanced to next substreams after each replication
            // antithetic option will control this (every other replication)
//TODO            setAllRVResetNextSubStreamOptions(false)
        } else {
            // tell the model to use the specifications from the experiment
//TODO            setAllRVResetStartStreamOptions(simulation.resetStartStreamOption)
//TODO            setAllRVResetNextSubStreamOptions(simulation.advanceNextSubStreamOption)
        }

        //TODO need to apply generic control types here someday
        if (simulation.hasControls()) {
            val cMap: Map<String, Double>? = simulation.getControls()
            if (cMap != null) {
                // extract controls and apply them
 //TODO               val k: Int = getControls().setControlsAsDoubles(cMap)
                val k = 0 //TODO delete after fixing previous to do
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

    internal fun runReplication(){
        if (mySimulation.maximumAllowedExecutionTimePerReplication > 0) {
            executive.maximumAllowedExecutionTime = mySimulation.maximumAllowedExecutionTimePerReplication
        }
        executive.initialize()
        setUpReplication()
        executive.executeAllEvents()
        replicationEndedActions()
        afterReplicationActions()
    }

    internal fun endExperiment(){
        afterExperimentActions()
    }
}

fun main() {
    val m = Model(Simulation())

    val me = ModelElement(m, "something")

    println(m)
    println(me)
}