package ksl.modeling.decision

/** §4.4 — the action pipeline: what a rule proposes, what is planned, and what is written. */

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
    internal val unavailable: BooleanArray?,
    /**
     *  §4.4.5. The model-authored atomic writes, each moving a declared group in one act.
     *
     *  Applied before the individual steps: a group exists because its members must move
     *  together, which makes it the part most likely to be what a joint constraint is about.
     */
    internal val batches: List<Batch> = emptyList()
) {

    /**
     *  Inert view: what will be written, in order — the batch groups first, then the individual
     *  levers in their decrease-before-increase order. Holding one cannot write anything.
     *
     *  A batched member appears here with `from` = `NaN`: the element does not read a batched
     *  lever's current value, because it never writes one individually and the group's function
     *  is what knows how to move it.
     */
    val writes: List<PlannedWrite> =
        batches.flatMap { b -> b.names.mapIndexed { i, n -> PlannedWrite(n, Double.NaN, b.values[i]) } } +
            steps.map { PlannedWrite(it.name, it.from, it.to) }

    data class PlannedWrite(val name: String, val from: Double, val to: Double)

    internal class Step(
        val name: String, val from: Double, val to: Double, val actuator: LeverActuator)

    /** One declared group and the values its [applyAll] will be handed, in declaration order. */
    internal class Batch(
        val names: List<String>, val values: DoubleArray, val applyAll: (DoubleArray) -> Unit)
}

/**
 *  §4.4.2 — the two-call contract between a proposed action and the model.
 *
 *  `prepare` validates and resolves; `apply` writes. There is deliberately no single call that does
 *  both, and the absence is what makes "**no lever is written when an action is rejected**" a
 *  property of the type rather than of an implementation. It is also what lets a test assert a plan
 *  without mutating a model.
 */
interface ActionBinding {
    fun prepare(action: DoubleArray): PreparedAction
    fun apply(plan: ActionPlan)
}

/**
 *  An action that could not be applied: outside a lever's bounds, non-integral on an integer
 *  domain, `NaN`, the wrong arity, or violating a joint constraint.
 *
 *  It carries **every** violation rather than the first, because a rule that returned three bad
 *  values should learn about three. When this is thrown, no lever has been written.
 */
class ActionValidationException(val violations: List<String>) :
    RuntimeException("Infeasible action; no lever was written. Violations: $violations")

/**
 *  A write that failed partway through applying a plan.
 *
 *  This is the one place the subsystem cannot promise all-or-nothing: by the time it is thrown some
 *  levers have moved and some have not, and the message says how many. Where that matters, a model
 *  author declares the group as an atomic batch instead (§4.4.5).
 */
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

/**
 *  An attempt to narrow a lever outside what the model declares, or to non-integral bounds on an
 *  integer domain.
 *
 *  The model's limits are a physical fact and the experiment's are a choice, so narrowing may only
 *  shrink. A rejected narrowing leaves **both** bounds as they were (§4.1.3.1).
 */
class NarrowingException(message: String) : RuntimeException(message)

/** Thrown by leverFor/rewardFor when the owner backs more than one declared lever or term. */
class AmbiguousLeverException(ownerName: String, val candidates: List<String>) :
    RuntimeException(
        "Model element '$ownerName' backs ${candidates.size} declared levers: $candidates. " +
            "Use leverRef(alias) to say which one."
    )

/**
 *  A reward term whose declared accumulation kind does not match what its source actually offers —
 *  a `TIME_INTEGRAL` declared against a plain `Response`, say.
 *
 *  Raised at the declaration, where the mistake was made, rather than at the first epoch.
 */
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

/**
 *  S§C.11.1 — thrown when `decide` is entered while a decision is already in progress.
 *
 *  The failure this prevents is silent rather than loud, which is why it is refused rather than
 *  tolerated. A nested decision runs the whole seven-step algorithm, applies an action to the model,
 *  and installs its own pending transition; control then returns to the outer call, which overwrites
 *  that pending with its own. The nested decision is applied and **never recorded**, the epoch count
 *  advances twice for one recorded row, and the epoch indices in the trajectory stop corresponding to
 *  the decisions that occurred. Nothing throws and nothing is visibly wrong.
 *
 *  The usual cause is named in the message rather than left to be discovered: a lever's declared write
 *  function is arbitrary modeler code, and applying an action runs it, so a write that reaches back
 *  into `decide` closes the loop. The repair is `requestDecision`, which only schedules — see
 *  S§C.11.4 Example 3.
 */
class ReentrantDecisionException(
    val elementName: String,
    val reason: String
) : IllegalStateException(
    "A decision was requested on '$elementName' with reason \"$reason\" while a decision was already " +
        "in progress. A decision may not be taken from inside another one: the nested decision would " +
        "be applied to the model and never recorded, and the trajectory's epoch indices would stop " +
        "corresponding to the decisions that occurred. The usual cause is a lever's write function " +
        "reaching back into decide() while the outer decision is applying its action. If a decision " +
        "as a consequence of a decision is what you mean, call requestDecision(reason) instead: it " +
        "schedules the epoch for after the current one returns, and is re-entrancy-safe by " +
        "construction."
)

/** How many drains one instant may take before a self-retriggering request is called a runaway. */
const val MAX_DRAINS_PER_INSTANT: Int = 1000

/**
 *  D11 — thrown when deferred requests keep arriving during the decisions that answer them.
 *
 *  A lever's write function may legitimately ask for a follow-up decision; `requestDecision` exists
 *  partly for that, and it cannot re-enter because it only schedules. What it is not is
 *  termination-safe: a write that asks *every* time asks forever, all at the same instant, because a
 *  zero-delay event lands at the current time. Before this the only bound was `maxEpochs`, so the
 *  fault announced itself as a hang and a billion-decision counter rather than as a diagnosis.
 *
 *  The shape is `ConditionalActionProcessor`'s, and for the identical reason: work that re-triggers
 *  itself while the clock stands still needs a cap and a name.
 */
class RunawayDecisionRequestException(
    val elementName: String,
    val time: Double,
    val drains: Int,
    val stillPending: List<String>
) : IllegalStateException(
    "Decision element '$elementName' has drained its request queue $drains times at time $time " +
        "without the clock advancing, and $stillPending is still pending. Something asks for a " +
        "decision every time a decision is taken -- most often a lever's write function calling " +
        "requestDecision unconditionally. requestDecision cannot re-enter, because it only " +
        "schedules, but it will not stop on its own: the guard belongs to the caller."
)

/**
 *  Thrown when a policy schedules an event during the call that asked it for an action.
 *
 *  R2 says the policy call neither advances the clock nor schedules events of its own, and until now
 *  nothing checked it. A rule that schedules is running simulation from inside a decision: whatever it
 *  scheduled lands in an interval no transition attributes, and the run stops being reproducible from
 *  its declared inputs. A rule that wants the model to do something says so through a lever.
 */
class PolicyScheduledEventException(
    val elementName: String,
    val policyLabel: String,
    val eventsScheduled: Long
) : IllegalStateException(
    "The rule '$policyLabel' on decision element '$elementName' scheduled $eventsScheduled event(s) " +
        "while it was being asked for an action. A policy may read the model and return an action; " +
        "it may not change the model, and scheduling is changing it. Whatever the rule wanted to " +
        "happen should be a lever, so that it is validated, applied in a defined order, and recorded " +
        "in the transition."
)
