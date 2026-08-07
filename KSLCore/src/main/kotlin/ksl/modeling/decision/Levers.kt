package ksl.modeling.decision

import ksl.modeling.decision.descriptor.LeverDomain
import ksl.modeling.decision.descriptor.LeverKind
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

/**
 *  Every lever declares what doing nothing means. This is the repair §8.2.3 specifies, and
 *  it is a required argument rather than an optional one.
 *
 *  Before it, a lever had an optional `read` and the design inferred everything from whether
 *  a modeler had supplied one. That conflated two different facts — *no reader was given* and
 *  *there is nothing to read* — with three measured consequences (§8.2.2): `HoldCurrentPolicy`
 *  could not run at all on a transactional model, so **the Level-2 safety argument of §6.2 was
 *  unavailable for the canonical MDP example**; §4.1.2.3's advice to always supply a reader
 *  pushed a modeler toward supplying a meaningless one; and having done so, the no-op elision
 *  silently dropped 1.5% of orders.
 *
 *  Naming the neutral value resolves all of it, because the reader stops being a separate
 *  optional thing and becomes the content of one of the two cases.
 */
sealed interface Neutral<in T> {

    /**
     *  A [LeverKind.SETTING]. Doing nothing is "leave it where it stands", so a reader is
     *  **required** — structurally, since there is nowhere else to put it, rather than by
     *  advice that a modeler may decline.
     */
    class Current<T>(val read: T.() -> Double) : Neutral<T>

    /**
     *  A [LeverKind.TRANSACTION]. Doing nothing is a declared constant, almost always zero.
     *  Nothing is read, and `supportsCurrentValue = false` now records a fact about the lever
     *  instead of an omission by its author.
     */
    class Value<T>(val amount: Double) : Neutral<T>
}

/** The descriptive half of a lever. Holding one cannot write anything (§4.3.1.1). */
data class LeverInfo(
    val name: String,
    val domain: LeverDomain,
    val kind: LeverKind,
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
