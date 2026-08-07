package ksl.modeling.decision

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.JointConstraint
import ksl.modeling.decision.descriptor.TerminationSource

/** STUB — Appendix E.2, §4.5. */

fun interface PolicyIfc {
    fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray
}

/**
 *  Everything a rule may know at a decision instant. Valid ONLY during the action()
 *  call that receives it — the element reuses one instance and updates the epoch-scoped
 *  fields, so retaining it is a bug.
 *
 *  The declared-shape fields are constant for the life of the element; they are here
 *  rather than in a separate creation-time context so that a rule needs no factory.
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
    val currentAction: DoubleArray
}

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
    fun beforeEpisode(episodeIndex: Int) {}
    /** The completed transition this rule's own action earned — what an adaptive rule needs. */
    fun onTransition(record: TransitionRecord) {}
    fun afterEpisode(episodeIndex: Int, source: TerminationSource) {}
    override fun close() {}
}

/** Change nothing. The Level-1 compatibility baseline (§6). */
object HoldCurrentPolicy : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray = ctx.currentAction
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
