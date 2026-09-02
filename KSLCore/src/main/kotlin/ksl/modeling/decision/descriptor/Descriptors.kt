package ksl.modeling.decision.descriptor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 *  §4.2 — the serializable description of a decision surface.
 *
 *  Plain data throughout: no `Model` reference, nothing that needs a built model to interpret.
 *  That is what lets a description be validated, stored, and read by something that has not built
 *  a model — and it is enforced, not merely intended: this package must not reference
 *  `ksl.simulation` or `ksl.modeling.*`, which `PackageLayeringTest` checks (E.1).
 */

/**
 *  The version of the descriptor's own format, carried so a stored description can say what it was
 *  written by. It is the *schema's* version, not the model's and not the library's.
 */
@Serializable
data class SchemaVersion(val major: Int = 1, val minor: Int = 0)

/**
 *  What values a lever can take, which decides how an out-of-range request may be repaired.
 *
 *  [CONTINUOUS] and [INTEGER] are ordered, so clamping means something — the nearest allowed value
 *  is a sensible reading of what was asked for. [CATEGORICAL] values are level indices standing for
 *  labels with no order, so clamping is *never* applied to one: coercing a request for level 9 into
 *  `fast` substitutes a category the rule never asked for, so such a request is rejected instead
 *  (§4.4.4).
 */
enum class LeverDomain { CONTINUOUS, INTEGER, CATEGORICAL }

/**
 *  What a lever is, distinguished by the only thing the machinery needs to tell apart:
 *  **what it means to do nothing** (§8.2.2, §8.2.3).
 *
 *  [SETTING] — a quantity the model *holds*: a capacity, a reorder point, a service rate.
 *  It has a current value, applying it twice is the same as applying it once, and doing
 *  nothing means writing nothing.
 *
 *  [TRANSACTION] — a quantity the model *does*: placing an order, dispatching a shipment.
 *  There is no "current order quantity", applying it twice acts twice, and doing nothing
 *  means acting with the neutral amount, which is an action rather than an abstention.
 */
enum class LeverKind { SETTING, TRANSACTION }

/**
 *  How a reward source accumulates, which is the one thing the epoch algorithm must know about it
 *  and the only place in the design that knows the three sources differ (§4.2.5).
 *
 *  [TIME_INTEGRAL] — a time-weighted quantity, whose accumulation is an area under a step function.
 *  Read as banked area **plus area in flight**: a time-weighted statistic banks the previous value's
 *  area only when a new value arrives, so for a quantity read every epoch and changed rarely the
 *  banked figure lags by the whole interval and every interval difference would be zero (§8.1.4).
 *
 *  [OBSERVATION_SUM] — the running sum of a response's observations.
 *
 *  [COUNTER_TOTAL] — a counter's value. A counter has nothing in flight.
 */
enum class RewardKind { TIME_INTEGRAL, OBSERVATION_SUM, COUNTER_TOTAL }

/**
 *  Whether a declared term is something to maximise or something to minimise.
 *
 *  Required per term rather than inferred from the sign of a rate, because a sign convention is
 *  exactly what a modeler gets wrong. A [COST] term is negated **once**, at declaration, so the
 *  rest of the system sees one quantity that is always maximised and no code downstream has to
 *  track senses (§4.2.5).
 */
enum class RewardSense { REWARD, COST }

/**
 *  What the element does with an action outside a lever's feasible range.
 *
 *  [REJECT] is the default and the safe one: the rule learns that what it asked for was not
 *  available. [CLAMP_THEN_REJECT] repairs the request on ordered domains and re-checks it once,
 *  rejecting if it is still infeasible; a repaired action is recorded with the rule's original
 *  vector alongside it, so the trajectory shows both what was asked and what happened (§4.8.3).
 *
 *  Under either policy, **a failing action writes no lever at all** (§4.4.2).
 */
enum class FeasibilityPolicy { REJECT, CLAMP_THEN_REJECT }

/**
 *  Why a decision episode ended — recorded on every emitted transition, because *that* it ended is
 *  not enough to interpret the last row (§4.6.3).
 *
 *  [NATURAL] is the modeler's declared terminal condition becoming true: the modeled system reached
 *  an ending it defines. [MAX_EPOCHS] is a declared cap on decisions. [RUN_LENGTH] is the
 *  replication ending underneath a sequence that had not finished. [MODEL_STOPPED] is the run being
 *  stopped from outside. [POLICY_ERROR] is the rule throwing, which is not caught.
 *
 *  The distinction matters beyond bookkeeping: learning algorithms bootstrap differently from an
 *  episode that *terminated* than from one that was merely *truncated*, and conflating the two is a
 *  known source of bias.
 */
enum class TerminationSource { NATURAL, MAX_EPOCHS, RUN_LENGTH, MODEL_STOPPED, POLICY_ERROR }

/**
 *  Where the state on a transition row was read (S§C.11.3).
 *
 *  A decision element no longer owns its timing: a modeler calls `decide(reason)` at a point they
 *  choose, or `requestDecision(reason)` to have the epoch taken in an event of the element's own.
 *  The two differ in a way a consumer of the trajectory cannot otherwise recover, and it is exactly
 *  the way that matters to a consumer.
 *
 *  [IMMEDIATE] — the state was read at the caller's call site. The model was inside some other event
 *  action at the time, so the caller warranted the state's consistency (R2b); the library did not.
 *
 *  [DEFERRED] — the state was read in the element's own zero-delay event, so the model was between
 *  events. Weaker than a scheduled epoch's guarantee — such an event lands at the current time later
 *  in the event order, not at the end of the instant — but it is a guarantee rather than a warrant.
 *
 *  An enum rather than a boolean because a third provenance with stronger quiescence is anticipated:
 *  a decision executed from the executive's condition-scan phase, which the event-triggered work may
 *  want. Widening a published field later is the thing this avoids.
 */
enum class EpochProvenance { IMMEDIATE, DEFERRED }

/**
 *  How decision epochs are scheduled: [PERIODIC] at a fixed interval, or on a declared [CALENDAR]
 *  of instants. Event-triggered epochs — deciding *when a queue exceeds five* — are future work,
 *  and this enum is where a third kind would go.
 */
enum class EpochKind { PERIODIC, CALENDAR }

/**
 *  Which runs first when a decision epoch coincides with the element's warm-up (§4.6.4).
 *
 *  It exists because the ordering is a consequence of two settable priorities rather than a
 *  property of the design, so the intent has to be declarable in order to be checkable.
 */
enum class WarmUpOrdering { EPOCH_FIRST, WARM_UP_FIRST }

/**
 *  A reference to the model quantity a reward term reads, by name and by what kind of thing it is.
 *
 *  It exists so the descriptor can say *where a reward came from* without holding the object —
 *  which is the whole property that lets a description travel without a model.
 */
@Serializable
sealed interface SourceRef {
    val name: String
}

/** A reward source that is a `Response` or `TWResponse`. */
@Serializable
@SerialName("response")
data class ResponseRef(override val name: String) : SourceRef

/** A reward source that is a `Counter`. */
@Serializable
@SerialName("counter")
data class CounterRef(override val name: String) : SourceRef

/**
 *  A budget over several levers — the constraint that makes a multi-lever action a *joint* choice
 *  rather than several independent ones (§4.4.6).
 *
 *  It is checked over the values that will actually be **written**, not over what the rule asked
 *  for, because a constraint is a statement about what the model will hold and a lever forced to
 *  its neutral contributes the neutral (§4.4.6.3).
 */
@Serializable
sealed interface JointConstraint {
    val names: List<String>
}

/** The named levers must sum to exactly [total] — a fixed pool that is entirely allocated. */
@Serializable
@SerialName("sumEquals")
data class SumEquals(override val names: List<String>, val total: Double) : JointConstraint

/** The named levers may sum to at most [total] — a ceiling that need not be reached. */
@Serializable
@SerialName("sumAtMost")
data class SumAtMost(override val names: List<String>, val total: Double) : JointConstraint

/**
 *  One declared observation: what a rule may read, and what the *i*th entry of an observation
 *  vector means. Order is significant — the position in the descriptor's list is the position in
 *  the array (§4.2.3).
 *
 *  [unit] is optional and unverifiable. The library cannot check that a quantity declared in "jobs"
 *  is not really in server-units, and says so plainly; what it can do is carry the declaration
 *  where a rule can compare it against what the rule was written for and refuse (§4.2.4).
 */
@Serializable
data class ObservationDescriptor(
    val name: String,
    val domain: LeverDomain = LeverDomain.CONTINUOUS,
    val unit: String? = null
)

/**
 *  One declared lever: what a rule may write, and what the *i*th entry of an action vector means.
 *  Order is significant, as for observations.
 *
 *  It carries **both** limit pairs, and the distinction is the one a consumer most needs: the
 *  model's own physical envelope ([modelLowerLimit], [modelUpperLimit]) and the experiment's
 *  narrowing ([lowerBound], [upperBound]). An editor or a rule that presented the wrong pair would
 *  offer values this run has excluded, or refuse values it allows (§4.3.3).
 */
@Serializable
data class LeverDescriptor(
    val name: String,
    val domain: LeverDomain,
    /**
     *  Whether this lever is held or done (§8.2.3). A consumer that plans a sequence of
     *  actions needs it: repeating a SETTING is idempotent and repeating a TRANSACTION is
     *  not, and no other field distinguishes them.
     */
    val kind: LeverKind = LeverKind.SETTING,
    val modelLowerLimit: Double,
    val modelUpperLimit: Double,
    val lowerBound: Double,
    val upperBound: Double,
    /**
     *  True when 𝒳(s) narrows the bounds above at each epoch (§4.4.6). The reported numbers
     *  are then an ENVELOPE, not the feasible set, and a consumer that cannot handle a
     *  state-dependent set should refuse rather than take them for the action space.
     */
    val stateDependent: Boolean = false,
    val levels: List<String>? = null,
    /**
     *  What this lever is measured in — "staff", "units", "$/hour". Optional by design
     *  (§4.2.4, G.9 row 7): requiring it would be ceremony, and the library cannot verify a
     *  unit against the model. What it does with one is check that a joint constraint does
     *  not sum levers measured in different things, and name it in every violation message —
     *  and, through this descriptor, let a `ShapeAwarePolicyIfc` refuse a surface whose units
     *  are not what the rule was written for.
     */
    val unit: String? = null
)

/**
 *  One declared reward term: `(source, kind, rate, sense)`.
 *
 *  [rate] is the value as declared, **before** the sign convention of [RewardSense] is applied, so
 *  that a description reads the way the modeler wrote it rather than the way the machinery
 *  consumes it.
 */
@Serializable
data class RewardDescriptor(
    val name: String,
    val source: SourceRef,
    val kind: RewardKind,
    val rate: Double,
    val sense: RewardSense
)

/**
 *  When decisions happen. Exactly one of [interval] and [calendar] is meaningful, according to
 *  [kind].
 *
 *  [priority] is included because it is not decoration: it decides which of two elements deciding
 *  at one instant goes first, and it decides whether an epoch coinciding with the warm-up runs
 *  before or after it (§4.6.4).
 */
@Serializable
data class EpochDescriptor(
    val kind: EpochKind,
    val interval: Double? = null,
    val calendar: List<Double>? = null,
    val firstAtTimeZero: Boolean = false,
    val priority: Int = 100_000
)

/**
 *  How a decision episode can end, besides the replication ending underneath it.
 *
 *  [hasTerminalCondition] is a boolean rather than the condition itself, deliberately: the
 *  condition is a Kotlin lambda over model state, which is exactly the kind of thing a description
 *  that travels without a model cannot carry. What a consumer can be told is *that* one exists.
 */
@Serializable
data class EpisodeDescriptor(
    val maxEpochs: Int = Int.MAX_VALUE,
    val hasTerminalCondition: Boolean = false
)

/**
 *  The complete description of one element's decision surface — what it may read, what it may
 *  write, what it is scored on, and when it decides.
 *
 *  **Derived, never authored** (§4.1.5). It is computed from the declaration on demand rather than
 *  stored or written by hand, which is what makes staleness structurally impossible: there is no
 *  second source of truth to fall out of step with the model.
 *
 *  It is also the **authority for the positional convention**. An observation or action vector is a
 *  bare `DoubleArray`; what gives entry *i* its meaning is this object's *i*th list entry, which is
 *  why a stored trajectory is only interpretable alongside the descriptor that was in force when it
 *  was written (§4.2.3, §14.1).
 */
@Serializable
data class DecisionSurfaceDescriptor(
    val schemaVersion: SchemaVersion = SchemaVersion(),
    val name: String,
    val observations: List<ObservationDescriptor>,
    val levers: List<LeverDescriptor>,
    val constraints: List<JointConstraint> = emptyList(),
    val rewards: List<RewardDescriptor> = emptyList(),
    val epochs: EpochDescriptor,
    val episode: EpisodeDescriptor = EpisodeDescriptor(),
    val feasibility: FeasibilityPolicy = FeasibilityPolicy.REJECT
) {
    /** Exists so the codecs in `DescriptorCodecs.kt` can hang `fromJson`/`fromToml` off the type. */
    companion object
}

/** A stored description whose [SchemaVersion] this library cannot read. */
class SchemaVersionException(message: String) : RuntimeException(message)
