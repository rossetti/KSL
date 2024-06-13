package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.IntegerFrequencyIfc

/**
 * This class tabulates the frequency associated with
 * the integers presented to it based on the attached [variable].
 * Every value presented is interpreted as an integer
 * For every value presented a count is maintained.
 * There could be space/time performance issues if
 * the number of different values presented is large.
 * Use [lowerLimit] and [upperLimit] to limit the values
 * that can be observed. Values lower than the lower limit
 * are counted as underflow and values greater than the upper limit
 * are counted as overflow.
 *
 * The frequency tabulates all within replication observations regardless of replication.
 * That is, the frequency is based on every observation for every replication.  It observes
 * observations that may have been within a warmup period even if the modeler specifies
 * a warmup period.
 *
 * This class can be useful for tabulating a
 * discrete histogram over the values (integers) presented.
 *
 * @param variable the variable to observe
 * @param lowerLimit the defined lower limit of the integers, values less than this are not tabulated
 * @param upperLimit the defined upper limit of the integers, values less than this are not tabulated
 * @param name a name for the instance
 * @author rossetti
 */
class IntegerFrequencyResponse(
    variable: Variable,
    name: String? = "${variable.name}_Frequency",
    lowerLimit: Int = Int.MIN_VALUE,
    upperLimit: Int = Int.MAX_VALUE,
    private val myIntegerFrequency: IntegerFrequency = IntegerFrequency(name = name, lowerLimit = lowerLimit, upperLimit = upperLimit)
) : ModelElement(variable, name), IntegerFrequencyIfc by myIntegerFrequency {

    internal val myVariable = variable
    private val myObserver = VariableObserver()

    init {
        myVariable.attachModelElementObserver(myObserver)
    }

    /**
     * This class tabulates the frequency associated with
     * the integers presented to it based on the attached [variable].
     * Every value presented is interpreted as an integer
     * For every value presented a count is maintained.
     * There could be space/time performance issues if
     * the number of different values presented is large.
     * Use [intRange] to limit the values within the specified range
     * that can be observed. Values lower than the lower limit
     * are counted as underflow and values greater than the upper limit
     * are counted as overflow.
     *
     * The frequency tabulates all within replication observations regardless of replication.
     * That is, the frequency is based on every observation for every replication.  It observes
     * observations that may have been within a warmup period even if the modeler specifies
     * a warmup period.
     *
     * @param variable the variable to observe
     * @param intRange the defined integer range for observations
     * @param name a name for the instance
     * @author rossetti
     */
    constructor(
        variable: Variable,
        name: String? = null,
        intRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE
    ) : this(variable, name, intRange.first, intRange.last)

    private inner class VariableObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val v = modelElement as Variable
            myIntegerFrequency.collect(v.value)
        }
    }

    /**
     *  Causes the histogram response to stop observing the underlying
     *  response.
     */
    fun stopCollecting(){
        myVariable.detachModelElementObserver(myObserver)
    }

    /**
     *  Causes the histogram response to start observing the underlying response
     */
    fun startCollecting(){
        if (!myVariable.isModelElementObserverAttached(myObserver)){
            myVariable.attachModelElementObserver(myObserver)
        }
    }

    override fun beforeExperiment() {
        myIntegerFrequency.reset()
    }

    override fun toString(): String {
        return myIntegerFrequency.toString()
    }

}