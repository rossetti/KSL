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

package ksl.modeling.variable

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
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
 *  @param allowedDomain the validity interval, defaults to [0.0, POSITIVE_INFINITY]
 *  @param name the name of the variable, will be auto-defined if null
 */
open class Variable(
    parent: ModelElement,
    theInitialValue: Double = 0.0,
    allowedDomain: Interval = Interval(),
    name: String? = null
) : ModelElement(parent, name), VariableIfc {
    init {
        require(allowedDomain.contains(theInitialValue)) { "The initial value $theInitialValue must be within the specified limits: $allowedDomain" }
    }

    override val domain: Interval = allowedDomain

    /**
     * Sets the initial value of the variable. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    @set:KSLControl(
        controlType = ControlType.DOUBLE
    )
    override var initialValue: Double = theInitialValue
        set(value) {
            require(domain.contains(value)) { "The initial value, $value must be within the specified range for the variable: $domain" }
            if (model.isRunning) {
                Model.logger.info { "The user set the initial value during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    private var myValue: Double = theInitialValue

    override var value: Double
        get() = myValue
        set(newValue) = assignValue(newValue)

    protected open fun assignValue(newValue: Double){
        require(domain.contains(newValue)) { "The value $newValue must be within the specified range for the variable : $domain" }
        previousValue = myValue // remember the previous value
        previousTimeOfChange = timeOfChange // remember the previous change time
        myValue = newValue// remember the new value
        timeOfChange = time
        notifyModelElementObservers(Status.UPDATE)
    }

    /**
     * Assigns the value of the variable to the supplied value. Ensures that
     * time of change is 0.0 and previous value and previous time of
     * change are the same as the current value and current time without
     * notifying any update observers
     *
     * @param value the initial value to assign
     */
    protected open fun assignInitialValue(value: Double) {
        require(domain.contains(value)) { "The initial value, $value must be within the specified range of the variable: $domain" }
        myValue = value
        timeOfChange = 0.0
        previousValue = value
        previousTimeOfChange = timeOfChange
    }

    /**
     *  The previous value, before the current value changed
     */
    override var previousValue: Double = theInitialValue
        protected set

    override var timeOfChange: Double = 0.0
        protected set

    override var previousTimeOfChange: Double = 0.0
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
        sb.appendLine(toString())
        sb.append("Time = ")
        sb.append(time)
        sb.append("\t")
        sb.append("Previous time = ")
        sb.append(previousTimeOfChange)
        sb.append("\t")
        sb.append("Previous value = ")
        sb.append(previousValue)
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