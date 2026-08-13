package ksl.modeling.decision

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.JointConstraint
import ksl.modeling.decision.descriptor.TerminationSource

/** §4.5 — the policy seam and the context a rule is given at a decision instant. */

fun interface PolicyIfc {
    fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray
}

/**
 *  Everything a rule may know at a decision instant.
 *
 *  The element reuses one instance and updates the epoch-scoped members in place, so a
 *  retained context would answer questions about a later epoch than the one the rule thinks
 *  it is in. That used to be documented as "retaining it is a bug" and nothing prevented it;
 *  it is now **enforced** — every epoch-scoped member throws [StaleDecisionContextException]
 *  when read outside the `action` call that supplied it (§4.5.3, G.9 row 6).
 *
 *  The declared-shape members are exempt, and the split is the point: they are constant for
 *  the life of the element, so retaining *them* is harmless and a rule that wants to keep the
 *  lever names may. What a rule may not keep is anything that means something different at
 *  the next epoch.
 */
interface DecisionContext {

    // ---- When the decision is happening.
    val simulationTime: Double
    val intervalSinceLastEpoch: Double
    val remainingRunLength: Double
    val epochIndex: Int
    val replicationId: Int

    // ---- What the decision may do. Constant; set once at construction.
    val elementName: String
    val modelName: String
    val observationNames: List<String>
    val leverNames: List<String>

    /**
     *  What each observation and each lever is measured in, positionally, `null` where none
     *  was declared (§4.2.4).
     *
     *  A `Double` in a positional array carries no units, and §4.2.4 states plainly that a
     *  wrong-but-plausible declaration — jobs where server-units were meant — is undetectable
     *  by anything in this design. These are what let a rule detect it *for itself*: a rule
     *  that knows what it was written for can compare, in `configure` or at the first epoch,
     *  and refuse rather than produce a plausible wrong answer. The library cannot make that
     *  check because only the rule knows the answer; what it can do is carry the question.
     *
     *  Declared shape, not epoch-scoped: constant for the life of the element.
     */
    val observationUnits: List<String?>
    val leverUnits: List<String?>
    /** Indexed, not mapped: read at every epoch (§4.2.3). */
    val leverBounds: List<ClosedFloatingPointRange<Double>>
    val constraints: List<JointConstraint>
    /** Total of the budget governing the lever at [leverIndex], or null if it is in none. */
    fun budgetTotal(leverIndex: Int): Double?

    /**
     *  What may be done NOW: the feasible set 𝒳(s) as an object (§4.4.6.5).
     *
     *  Epoch-scoped and pure. Bounds, membership, violations and enumeration all live on
     *  [ActionSet] rather than being spread across this interface, so that a rule which
     *  searches 𝒳(s) can be handed the set instead of rebuilding it from parts.
     */
    val actions: ActionSet

    // ---- Where the levers stand right now (§4.10.2 step 6).
    /**
     *  The current value of each lever, or `NaN` in any position whose lever is a
     *  [ksl.modeling.decision.descriptor.LeverKind.TRANSACTION] — a transaction has no
     *  current value, and `NaN` says so rather than inventing one. A rule that wants
     *  "do nothing" wants [neutralAction], which is defined for both kinds.
     */
    val currentAction: DoubleArray

    /**
     *  **Do nothing**, as each lever declared it (§8.2.3): the current value of a setting,
     *  the declared amount of a transaction.
     *
     *  This is what makes the Level-2 baseline of §6.2 available to every model rather than
     *  only to models whose decisions happen to be settings — the difference between a safety
     *  property and a convention.
     */
    val neutralAction: DoubleArray
}

/**
 *  Thrown when a [DecisionContext] is read outside the `action` call that supplied it.
 *
 *  §4.5.3 stated the rule and the design relied on rules obeying it. The failure it prevents
 *  is silent: a policy that stashes the context in a field and reads `simulationTime` from a
 *  background thread, or at the next epoch, gets a well-formed number belonging to a different
 *  decision. There is nothing to notice.
 */
class StaleDecisionContextException(
    val elementName: String,
    val member: String,
    val decisionsSince: Long
) : IllegalStateException(
    "`$member` was read outside the action() call that supplied this DecisionContext " +
        "(element '$elementName'; " +
        (if (decisionsSince == 0L) "the decision it belongs to has since ended"
         else "$decisionsSince decision(s) have been made since") +
        "). A context is valid only during the call that receives it. Copy what you need out " +
        "of it during action() — the values, not the context (§4.5.3)."
)

/**
 *  Implement when a rule requires something of the declared shape, or must BUILD
 *  something from it — a discretization grid, a table sized to the action vector,
 *  a network's input layer.
 *
 *  [configure] is called once each time this policy is assigned to an element, before
 *  any replication and never during one. Validate by throwing; build by assigning to
 *  the policy's own fields. A policy that builds state is a class, not an object.
 */
interface ShapeAwarePolicyIfc : PolicyIfc {
    fun configure(surface: DecisionSurfaceDescriptor)
}

/** A policy with a lifecycle: per-replication state to reset, or a resource to release. */
interface ManagedPolicyIfc : PolicyIfc, AutoCloseable {

    /**
     *  §4.7. Acquire whatever this rule needs for **one experiment**. Called from the element's
     *  `beforeExperiment()`, so it runs again on every `model.simulate()`.
     */
    fun beforeExperiment() {}

    fun beforeEpisode(episodeIndex: Int) {}

    /** The completed transition this rule's own action earned — what an adaptive rule needs. */
    fun onTransition(record: TransitionRecord) {}

    fun afterEpisode(episodeIndex: Int, source: TerminationSource) {}

    /**
     *  §4.7. Release whatever [beforeExperiment] acquired. Called from the element's
     *  `afterExperiment()`, and **paired with it on every run** — a model simulated three times
     *  calls both three times.
     *
     *  This is where per-run teardown goes. It is deliberately not [close].
     */
    fun afterExperiment() {}

    /**
     *  End of life, not end of experiment.
     *
     *  **The element does not call this at `afterExperiment()`**, and the first version did — which
     *  meant a model simulated twice ran its second experiment against a policy whose resources had
     *  already been released, with no error and no sign in the output. Measured on a two-run, two-
     *  replication model: the policy was closed after run 1 and then used **twelve** more times.
     *  Repeated evaluation of one model is not an exotic case; it is what a parameter sweep and
     *  simulation optimization (B.5) both do.
     *
     *  The element closes only what the element opened (§4.7). It opens a sink, through the factory
     *  at `beforeExperiment()`, so it closes the sink. It never opened the policy — the user
     *  constructed it and assigned it — so the only moment it closes one is when that policy is
     *  **replaced**, which is the user saying they are finished with it here. The last policy
     *  assigned is the user's to close, exactly as `WelchFileObserver` is
     *  (`observers/welch/WelchFileObserver.kt:100`, whose own `close` is documented "Call when done
     *  with the observer. Idempotent.").
     *
     *  Implementations should be idempotent, for the same reason that one is.
     */
    override fun close() {}
}

/**
 *  Do nothing — every lever takes its declared neutral value. The Level-2 compatibility
 *  baseline of §6.2.
 *
 *  This was `HoldCurrentPolicy`, returning `ctx.currentAction`, which worked only for models
 *  whose levers are all settings. On a transactional model `currentAction` is `NaN` in every
 *  position and the baseline could not run at all (§8.2.2), so the one arm §4.1.10 requires of
 *  every worked example had to be hand-written per model. With a declared neutral it is
 *  generic again.
 */
object NeutralPolicy : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray = ctx.neutralAction
}

/** A constant action. Arity is checked against the declaration at assignment. */
class FixedPolicy(private val values: DoubleArray) : ShapeAwarePolicyIfc {
    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(values.size == surface.levers.size) {
            "FixedPolicy has ${values.size} values but the element declares ${surface.levers.size} levers."
        }
    }
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray = values.copyOf()
}
