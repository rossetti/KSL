package ksl.sdm.capture

import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink

/** §4.8.2 — sink implementations. The contract lives in ksl.modeling.decision (D.19). */

object NullSink : TransitionSink {
    override fun write(record: TransitionRecord) {}
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
