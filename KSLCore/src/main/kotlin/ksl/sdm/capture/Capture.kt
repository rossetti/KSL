package ksl.sdm.capture

import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink

/** §4.8.2 — sink implementations. The contract lives in ksl.modeling.decision (D.19). */

object NullSink : TransitionSink {
    override fun write(record: TransitionRecord) {}
}

class MemorySink : TransitionSink {
    private val rows = mutableListOf<TransitionRecord>()
    val records: List<TransitionRecord> get() = rows
    override fun write(record: TransitionRecord) { rows.add(record) }
}
