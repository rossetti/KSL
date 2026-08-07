package ksl.modeling.decision

/** STUB — Appendix E.2, §4.4. */

sealed interface PreparedAction {
    data class Ready(val plan: ActionPlan) : PreparedAction
    data class Invalid(val violations: List<String>) : PreparedAction
}

class ActionPlan internal constructor(internal val steps: List<Step>) {

    /** Inert view: what will be written, in order. Holding one cannot write anything. */
    val writes: List<PlannedWrite> = steps.map { PlannedWrite(it.name, it.from, it.to) }

    data class PlannedWrite(val name: String, val from: Double, val to: Double)

    internal class Step(
        val name: String, val from: Double, val to: Double, val actuator: LeverActuator)
}

interface ActionBinding {
    fun prepare(action: DoubleArray): PreparedAction
    fun apply(plan: ActionPlan)
}

class ActionValidationException(val violations: List<String>) :
    RuntimeException("Infeasible action; no lever was written. Violations: $violations")

class ActionApplicationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class BindingException(val unresolved: String, val available: List<String>) :
    RuntimeException("Cannot resolve '$unresolved'. Available: $available")

class NarrowingException(message: String) : RuntimeException(message)

/** Thrown by leverFor/rewardFor when the owner backs more than one declared lever or term. */
class AmbiguousLeverException(ownerName: String, val candidates: List<String>) :
    RuntimeException(
        "Model element '$ownerName' backs ${candidates.size} declared levers: $candidates. " +
            "Use leverRef(alias) to say which one."
    )

class RewardKindException(message: String) : RuntimeException(message)

/**
 *  A form the DSL accepts and the library does not yet carry (G.9 row 11).
 *
 *  Thrown **at the declaration**, not at the eventual use. A modeler who writes something
 *  legal should learn immediately that it is not yet supported and which milestone carries
 *  it — not run a study and find the value missing. When the milestone lands, the throw is
 *  replaced by the implementation and nothing else changes.
 */
class NotDeclarableYetException(
    val form: String,
    val milestone: String,
    val section: String
) : RuntimeException(
    "`$form` is specified in $section and is not implemented yet; it is scheduled for $milestone. " +
        "It fails here, at the declaration, rather than later at the point of use."
)
