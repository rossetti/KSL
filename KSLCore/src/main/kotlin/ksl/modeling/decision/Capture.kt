package ksl.modeling.decision

import kotlinx.serialization.Serializable
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor

/**
 *  §4.8.2. The capture CONTRACT lives with the element that produces
 *  records; ksl.modeling.decision.capture provides implementations. Dependency inversion: without it the
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
 *  `ksl.modeling.decision.capture.TabularSink`.
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
 *  does own is one it created itself, which is what [RollingSink] is for.
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

/**
 *  Moved here from the implementations package, because it is not one.
 *
 *  `NullSink`, `MemorySink` and `TabularSink` are about *where rows go* — destinations, and genuinely
 *  implementations. This is about a sink's *lifetime*: it makes a fresh delegate per experiment and
 *  closes it, which is the `beginExperiment`/`endExperiment` protocol [TransitionSink] already
 *  defines. It is a decorator over the contract with no knowledge of any destination, and it belongs
 *  beside the contract.
 *
 *  It also had to move. `captureTo` in the declaration DSL attaches one, so while this lived
 *  downstream the element referred to the implementations package by fully-qualified name — a real
 *  compile-time dependency in both directions, and exactly the cycle §7.2 claims is inverted. The
 *  layering test could not see it, because it inspected imports and a qualified name is not one.
 *

 *  A sink that makes a **fresh delegate for every experiment** and closes it when that experiment
 *  ends (§4.8.2).
 *
 *  ### What it is for
 *
 *  A durable sink writes an artifact, and an artifact belongs to one run. `TabularSink` names its
 *  file from the provenance — which carries the experiment name — precisely so that §4.9's k-rule
 *  comparison, which runs one model k times under k policies, leaves k trajectories instead of
 *  overwriting one. A single long-lived `TabularSink` attached across those k runs cannot do that:
 *  its file was chosen before the first run.
 *
 *  So the per-experiment factory survives — as a sink, rather than as a second mechanism on the
 *  element. `DecisionElement` has exactly one way to be captured (attach a sink), and "a new file
 *  each run" is an ordinary decorator over it, the way `ksl.animation` layers
 *  `ReplicationSelectingSink` and `WindowedAnimationSink` over its base sinks. `captureTo` in the
 *  builder DSL is one line that attaches one of these.
 *
 *  ### Ownership
 *
 *  This sink DID construct its delegate, so this sink closes it — at `endExperiment`, not at
 *  `close`, because the delegate's life is the run's. [close] is idempotent and releases a
 *  delegate only if a run was cut short before `endExperiment` arrived.
 */
class RollingSink(
    private val factory: (RunProvenance) -> TransitionSink
) : TransitionSink {

    private var current: TransitionSink? = null

    /** The delegate for the run in progress, or null between runs. For tests and diagnostics. */
    val delegate: TransitionSink?
        get() = current

    override fun beginExperiment(provenance: RunProvenance) {
        // A previous run that ended abnormally could leave one behind; releasing it here is the
        // difference between a leaked file handle per run and a leaked file handle.
        current?.let { runCatching { it.close() } }
        current = factory(provenance)
    }

    override fun write(record: TransitionRecord) {
        current?.write(record)
    }

    override fun endExperiment() {
        val d = current
        current = null
        d?.close()
    }

    override fun close() {
        val d = current
        current = null
        d?.close()
    }
}
