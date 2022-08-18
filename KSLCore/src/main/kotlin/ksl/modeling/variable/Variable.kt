package ksl.modeling.variable

import ksl.simulation.ModelElement
import ksl.simulation.Simulation
import ksl.utilities.Interval

/**
 *  Simulation models may use many variables that define the state of the component/system being modeled.
 *  Within the context of stochastic simulation the values of the variables may change during the
 *  execution of a replication (i.e. the generation of a sample path). It is important to ensure that
 *  each replication starts in the same state (i.e. with the same initial conditions).  Thus, if variable
 *  values change during the replication, the value of the variable must be returned to its initial
 *  value prior to the execution of the next replication. The purpose of this class is to facilitate
 *  the specification of the initial value and the resetting to the initial value prior to the experiments and
 *  the subsequent replications.  Additionally, the value of a variable might need to be constrained to
 *  a legally specified range or interval.  Because of the random nature of simulation, model logic
 *  might attempt to set the value of a variable outside its legal set of values.  This class allows
 *  the specification of a valid interval for the variable.  If the user attempts to set the value outside
 *  this interval, then an exception will be thrown.  This facilitates validation of the model.
 *  @author rossetti@uark.edu
 *  @param parent the parent (containing) model element for this variable
 *  @param theInitialValue the initial value, default to 0.0
 *  @param theLimits the validity interval, defaults to [0.0, POSITIVE_INFINITY]
 *  @param name the name of the variable, will be auto-defined if null
 */
open class Variable(
    parent: ModelElement,
    theInitialValue: Double = 0.0,
    theLimits: Interval = Interval(0.0, Double.POSITIVE_INFINITY),
    name: String? = null
) : ModelElement(parent, name), VariableIfc {
//TODO aggregates?

    var limits: Interval = theLimits

    init {
        require(limits.contains(theInitialValue)) { "The initial value $theInitialValue must be within the specified limits: $limits" }
    }

    /**
     * Sets the initial value of the variable. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    override var initialValue: Double = theInitialValue
        set(value) {
            require(limits.contains(value)) { "The initial value, $value must be within the specified limits: $limits" }
            if (simulation.isRunning) {
                Simulation.logger.info { "The user set the initial value during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    private var myValue: Double = theInitialValue
    override var value: Double
        get() = myValue
        set(newValue) = assignValue(newValue)

    protected fun assignValue(newValue: Double){
        require(limits.contains(newValue)) { "The value $newValue must be within the specified limits : $limits" }
        previous = myValue // remember the previous value
        previousTimeOfChange = timeOfChange // remember the previous change time
        myValue = newValue// remember the new value
        timeOfChange = time
        //TODO notify observers
    }

    /**
     * Assigns the value of the variable to the supplied value. Ensures that
     * time of change is 0.0 and previous value and previous time of
     * change are the same as the current value and current time without
     * notifying any update observers
     *
     * @param value the initial value to assign
     */
    protected fun assignInitialValue(value: Double) {
        require(limits.contains(value)) { "The initial value, $value must be within the specified limits: $limits" }
        myValue = value
        timeOfChange = 0.0
        previous = myValue
        previousTimeOfChange = timeOfChange
    }

    /**
     *  The previous value, before the current value changed
     */
    override var previous: Double = theInitialValue //TODO should start at Double.NaN
        protected set

    override var timeOfChange: Double = 0.0 //TODO should start at Double.NaN
        protected set
    override var previousTimeOfChange: Double = 0.0 //TODO should start at Double.NaN
        protected set

    override fun beforeExperiment() {
        super.beforeExperiment()
        assignInitialValue(initialValue)
    }

    override fun initialize() {
        super.initialize()
        assignInitialValue(initialValue)
    }
    open fun asString(): String {
        val sb = StringBuilder()
        sb.append(toString())
        sb.appendLine()
        sb.append("Time = ")
        sb.append(time)
        sb.append("\t")
        sb.append("Previous time = ")
        sb.append(previousTimeOfChange)
        sb.append("\t")
        sb.append("Previous value = ")
        sb.append(previous)
        sb.appendLine()
        sb.append("Current time = ")
        sb.append(timeOfChange)
        sb.append("\t")
        sb.append("Current value = ")
        sb.append(value)
        sb.appendLine()
        return sb.toString()
    }
}