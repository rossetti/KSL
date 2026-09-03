package ksl.modeling.decision

import ksl.modeling.decision.descriptor.LeverDomain
import ksl.modeling.decision.descriptor.LeverKind
import ksl.utilities.GetValueIfc

/** §4.3 — actuators: the only things that write a model during a decision. */

internal interface LeverActuator {
    val domain: LeverDomain
    val lowerBound: Double
    val upperBound: Double
    fun apply(value: Double)
}

/**
 *  An actuator whose lever has a readable current value — which is to say, a `SETTING`.
 *
 *  The element needs it to plan a write as `from → to`, which is what allows a setting already at
 *  its target to be **elided** rather than written. That elision is not an optimisation: writing a
 *  value back to a KSL response collects an observation and notifies observers whether or not the
 *  value changed, so without it a do-nothing rule would be visible in the output and §6.2's
 *  Level-2 guarantee would fail at the fine grain.
 */
internal interface StatefulLeverActuator : LeverActuator {
    fun currentValue(): Double
}

/**
 *  §4.4.5 — a model-authored write that moves several levers in **one act**.
 *
 *  The element's ordering rule keeps the common case feasible at every intermediate point, and that
 *  is not atomicity: between two individual writes a joint constraint can be momentarily violated,
 *  and a model that observes itself mid-action can see it. The library cannot close that itself,
 *  because the writes have synchronous consequences inside the model and buffering them under a
 *  lock would not help. This is the escape hatch: the author supplies the one function that moves
 *  the whole group, and the element calls it once.
 *
 *  [applyAll] receives a value for **every** member in [names] order, including members that did
 *  not move — a batch is one call and cannot be handed a partial vector.
 */
internal interface BatchLeverActuator {
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
    val levels: List<String>? = null,
    val unit: String? = null
)

/** Read-only view of a Response, TWResponse, or Counter used as a reward source. */
interface RewardSourceCIfc : GetValueIfc {
    val name: String
    /** The accumulated quantity whose difference between epochs is the interval's contribution. */
    fun accumulated(): Double
}

/**
 *  How much of a decision surface's units were declared, and how much of the checking that
 *  makes possible actually ran (§4.2.4, G.9 row 7).
 *
 *  `unit` is optional, so every check built on it is conditional on someone having declared
 *  one. That is a defensible design and an easy one to let rot: a surface where nothing
 *  declares a unit passes every unit check trivially, and looks identical in a green test run
 *  to one where every check fired. This type is what makes the difference visible, and it is
 *  the answer to D.10's objection that a field documenting an unenforced invariant is a fault —
 *  the invariant is enforced where it can be, and the coverage of that enforcement is reported
 *  rather than assumed.
 */
data class UnitCoverage(
    val observationsDeclared: Int,
    val observations: Int,
    val leversDeclared: Int,
    val levers: Int,
    /** Constraints where every summed lever declared a unit, so the sum was fully checked. */
    val constraintsChecked: Int,
    /** Constraints where only some did — checked in part, and knowingly so. */
    val constraintsPartlyChecked: Int,
    val constraints: Int
) {
    val fullyChecked: Boolean
        get() = constraintsPartlyChecked == 0 && constraintsChecked == constraints

    override fun toString(): String =
        "units: observations $observationsDeclared/$observations, levers $leversDeclared/$levers, " +
            "constraints fully checked $constraintsChecked/$constraints" +
            (if (constraintsPartlyChecked > 0) " ($constraintsPartlyChecked partly)" else "")
}
