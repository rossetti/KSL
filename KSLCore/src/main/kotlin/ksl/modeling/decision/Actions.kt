package ksl.modeling.decision

/** STUB — Appendix E.2, §4.4. */

sealed interface PreparedAction {
    data class Ready(val plan: ActionPlan) : PreparedAction
    data class Invalid(val violations: List<String>) : PreparedAction
}

class ActionPlan internal constructor(
    internal val steps: List<Step>,
    /**
     *  §4.8.3. The resolved target of **every** lever, in declaration order — what the model ends
     *  the epoch holding, which is what a transition's `action` records.
     *
     *  Not derivable from [steps]: a SETTING whose target equals its source is elided rather than
     *  written, so a lever that was already at its value has no step. Reconstructing the vector
     *  from the steps would silently drop exactly those levers.
     */
    internal val applied: DoubleArray,
    /**
     *  §4.4.6.3. Per lever, in declaration order: was its feasible set empty at this epoch, so that
     *  it took its declared neutral rather than anything the rule chose? `null` when every lever
     *  had something to choose from, which is the ordinary case and costs a row nothing.
     */
    internal val unavailable: BooleanArray?
) {

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

/**
 *  A declared name that does not resolve against the element it was given to.
 *
 *  [hint] carries the *reason* when the plain "no such name" reading would be wrong — most often a
 *  `LeverRef` that resolves perfectly well, just not here. E.3 requires the message to name the
 *  unresolved item and list what was available; when the two elements share an alias, that pair
 *  alone reads as a contradiction ("`staff` is unavailable; available: `staff`"), so the hint is
 *  what makes the message actionable rather than baffling.
 */
class BindingException(
    val unresolved: String,
    val available: List<String>,
    val hint: String? = null
) : RuntimeException(
    "Cannot resolve '$unresolved'. Available: $available" + (hint?.let { ". $it" } ?: "")
)

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
 *
 *  [milestone] is the weak part of this message and is known to be. [section] is durable — it
 *  names where the form is specified — while a milestone label is a project-plan coordinate
 *  that means nothing to a reader outside the project and goes stale when the plan is redrawn.
 *  It has gone stale twice: once when §7.1.1 moved work between milestones, and once when a
 *  step was reported complete while still carrying a refusal that pointed at it. The label is
 *  kept because during development it is the most useful thing to tell an implementer, and it
 *  should not survive into a release — at which point every remaining use of this exception is
 *  itself a release blocker, so the question resolves itself.
 */
class NotDeclarableYetException(
    val form: String,
    val milestone: String,
    val section: String
) : RuntimeException(
    "`$form` is specified in $section and is not implemented yet; it is scheduled for $milestone. " +
        "It fails here, at the declaration, rather than later at the point of use."
)
