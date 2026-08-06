package ksl.modeling.decision

import ksl.modeling.decision.descriptor.LeverDomain
import ksl.utilities.GetValueIfc

/** STUB — Appendix E.2. Actuators: the only things that write a model during a decision. */

interface LeverActuator {
    val domain: LeverDomain
    val lowerBound: Double
    val upperBound: Double
    fun apply(value: Double)
}

interface StatefulLeverActuator : LeverActuator {
    fun currentValue(): Double
}

interface BatchLeverActuator {
    val names: List<String>
    fun applyAll(values: DoubleArray)
}

/** The descriptive half of a lever. Holding one cannot write anything (§4.3.1.1). */
data class LeverInfo(
    val name: String,
    val domain: LeverDomain,
    val modelLowerLimit: Double,
    val modelUpperLimit: Double,
    val supportsCurrentValue: Boolean,
    val batchGroup: String? = null,
    val levels: List<String>? = null
)

/** Read-only view of a Response, TWResponse, or Counter used as a reward source. */
interface RewardSourceCIfc : GetValueIfc {
    val name: String
    /** The accumulated quantity whose difference between epochs is the interval's contribution. */
    fun accumulated(): Double
}
