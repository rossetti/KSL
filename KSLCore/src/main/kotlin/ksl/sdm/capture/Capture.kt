package ksl.sdm.capture

import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink

/** STUB — Appendix E.2, §4.8.2. Sink implementations. The contract is in ksl.modeling.decision. */

object NullSink : TransitionSink {
    override fun write(record: TransitionRecord) {}
}

class MemorySink : TransitionSink {
    private val rows = mutableListOf<TransitionRecord>()
    val records: List<TransitionRecord> get() = rows
    override fun write(record: TransitionRecord) { rows.add(record) }
}
