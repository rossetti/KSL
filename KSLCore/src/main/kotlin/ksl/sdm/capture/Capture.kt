package ksl.sdm.capture

import ksl.modeling.decision.RunProvenance
import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink

/** §4.8.2 — sink implementations. The contract lives in ksl.modeling.decision (D.19). */

/**
 *  A sink that discards everything.
 *
 *  Kept as a neutral element for composition — a `DecisionCapture` selector that wants to say
 *  "attach nothing here" returns `null` rather than this — and because an element with no sinks
 *  attached does not construct a record at all, which is cheaper than handing one to an empty
 *  method.
 */
object NullSink : TransitionSink {
    override fun write(record: TransitionRecord) {}
}

/**
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

/**
 *  A sink that keeps every record in a list.
 *
 *  For tests and for short runs a user wants to inspect immediately. It needs no `Model` — a record
 *  carries its own identity — so it can be asserted against standalone, which is what makes the
 *  sink contract testable before any durable implementation exists (§4.8.2).
 *
 *  It is bounded only by the run. A study large enough to matter wants a durable sink.
 */
class MemorySink : TransitionSink {
    private val rows = mutableListOf<TransitionRecord>()
    val records: List<TransitionRecord> get() = rows
    override fun write(record: TransitionRecord) { rows.add(record) }
}
