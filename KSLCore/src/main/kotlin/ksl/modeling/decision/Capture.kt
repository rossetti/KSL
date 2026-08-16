package ksl.modeling.decision

import kotlinx.serialization.Serializable
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor

/**
 *  §4.8.2. The capture CONTRACT lives with the element that produces
 *  records; ksl.sdm.capture provides implementations. Dependency inversion: without it the
 *  two packages would import each other and E.1's one-way layering would be a fiction.
 */

/**
 *  What produced a set of transitions: which model, which experiment, which element, which rule —
 *  and the [descriptor] that gives the rows their meaning.
 *
 *  **`@Serializable` because a stored trajectory has to carry this with it.** A row is positional:
 *  `a_Mode = 2.0` is uninterpretable without the declaration saying that lever is CATEGORICAL over
 *  `["slow", "normal", "fast"]`, that it is a SETTING rather than a TRANSACTION, and where its
 *  bounds are. Column names carry position-to-name and nothing else. So a durable sink writes this
 *  object beside its rows and a reader refuses a trajectory that arrives without it — see
 *  `ksl.sdm.capture.TabularSink`.
 */
@Serializable
data class RunProvenance(
    val modelName: String,
    val experimentName: String,
    val elementName: String,
    val policyLabel: String,
    val descriptor: DecisionSurfaceDescriptor
)

/**
 *  §4.8.2. A write-only consumer with a per-experiment lifetime.
 *
 *  ### The shape, and why it is this one
 *
 *  A sink is a **long-lived object that is attached to an element and told when a run starts and
 *  stops** — `DecisionElement.attachTransitionSink`, then [beginExperiment] at the element's
 *  `beforeExperiment()` and [endExperiment] at its `afterExperiment()`. That is the same shape as
 *  `ksl.animation.AnimationSink`, which has `onReplicationStart`/`onReplicationEnd`/`onExperimentEnd`
 *  with no-op defaults for exactly this reason: a sink that only wants rows implements one method.
 *
 *  The handshake is not decoration. A sink needs [RunProvenance] to write anything a stranger can
 *  read, and two of provenance's five fields — the experiment name and the policy label — **change
 *  between runs of the same model**: §4.9's k-rule comparison assigns k policies to one element in
 *  a loop. Handing provenance to a sink's constructor would freeze those at the wrong moment, and
 *  the descriptor is computed at `beforeExperiment()` rather than stored so it cannot be stale
 *  (§4.1.5). So provenance arrives per experiment, at [beginExperiment].
 *
 *  ### Who closes it
 *
 *  Whoever constructed it. [endExperiment] is the flush point and the element calls it; [close] is
 *  ownership and the element does not. This follows `ksl.observers.ResponseTrace`, which is
 *  `AutoCloseable`, is constructed by the user, and is closed by the user. The one sink the element
 *  does own is one it created itself, which is what `ksl.sdm.capture.RollingSink` is for.
 */
interface TransitionSink : AutoCloseable {
    /**
     *  A run is beginning. Called once per experiment, per attachment, before any [write].
     *
     *  The default does nothing, so a sink that does not care about provenance — a counter, an
     *  in-memory list, a live view — need not implement it.
     */
    fun beginExperiment(provenance: RunProvenance) {}

    fun write(record: TransitionRecord)

    /**
     *  The run is over and no further [write] will arrive for it. Flush here. The sink may be
     *  told [beginExperiment] again for the next run — attachments outlive experiments.
     */
    fun endExperiment() {}

    override fun close() {}
}

