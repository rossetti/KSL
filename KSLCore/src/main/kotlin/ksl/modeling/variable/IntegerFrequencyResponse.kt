package ksl.modeling.variable

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.ModelElement
import ksl.utilities.IdentityIfc
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.IntegerFrequencyIfc

interface FrequencyResponseCIfc : IdentityIfc, ParentNameIfc {
    val frequencyResponse: IntegerFrequencyIfc
}

/**
 * This class tabulates the frequency associated with
 * the integers presented to it.
 * Every value presented is interpreted as an integer
 * For every value presented a count is maintained.
 * There could be space/time performance issues if
 * the number of different values presented is large.
 * Use lowerLimit and upperLimit to limit the values
 * that can be observed. Values lower than the lower limit
 * are counted as underflow, and values greater than the upper limit
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
 * @param parent the parent of this model element
 * @param lowerLimit the defined lower limit of the integers, values less than this are not tabulated
 * @param upperLimit the defined upper limit of the integers, values less than this are not tabulated
 * @param name a name for the instance
 * @author rossetti
 */
class IntegerFrequencyResponse @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
    lowerLimit: Int = Int.MIN_VALUE,
    upperLimit: Int = Int.MAX_VALUE
) : ModelElement(parent, name), FrequencyResponseCIfc {

    /**
     * This class tabulates the frequency associated with
     * the integers presented to it.
     * Every value presented is interpreted as an integer
     * For every value presented a count is maintained.
     * There could be space/time performance issues if
     * the number of different values presented is large.
     * Use [intRange] to limit the values within the specified range
     * that can be observed. Values lower than the lower limit
     * are counted as underflow, and values greater than the upper limit
     * are counted as overflow.
     *
     * The frequency tabulates all within replication observations regardless of replication.
     * That is, the frequency is based on every observation for every replication.  It observes
     * observations that may have been within a warmup period even if the modeler specifies
     * a warmup period.
     *
     * @param parent the variable to observe
     * @param intRange the defined integer range for observations
     * @param name a name for the instance
     * @author rossetti
     */
    @Suppress("unused")
    constructor(
        parent: ModelElement,
        name: String? = null,
        intRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE
    ) : this(parent, name, intRange.first, intRange.last)

    private val myIntegerFrequency: IntegerFrequency = IntegerFrequency(
        name = "${name}_Frequency",
        lowerLimit = lowerLimit,
        upperLimit = upperLimit)

    override val frequencyResponse: IntegerFrequencyIfc
        get() = myIntegerFrequency

    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectionOn : Boolean = true

    var value: Int = 0
        set(value) {
            field = value
            if (collectionOn){
                myIntegerFrequency.collect(value)
            }
        }

    fun collect(value: Int) {
        this.value = value
    }

    /**
     *  Converts the double to an integer by truncating (toInt())
     *  before the collection
     */
    fun collect(value: Double) {
        this.value = value.toInt()
    }

    override fun beforeExperiment() {
        collectionOn = true
        myIntegerFrequency.reset()
    }

    override fun toString(): String {
        return myIntegerFrequency.toString()
    }

}