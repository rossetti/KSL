package ksl.sdm.capture

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.TerminationSource

/** STUB — Appendix E.2, §4.8. */

data class RunProvenance(
    val modelName: String,
    val experimentName: String,
    val elementName: String,
    val policyLabel: String,
    val descriptor: DecisionSurfaceDescriptor
)

data class TransitionRecord(
    val replicationId: Int,
    val epochIndex: Int,
    val time: Double,
    val tau: Double,
    val state: DoubleArray,
    val action: DoubleArray,
    val reward: Double,
    val successorState: DoubleArray,
    val terminated: Boolean,
    val truncated: Boolean,
    val source: TerminationSource? = null
)

interface TransitionSink : AutoCloseable {
    fun open(provenance: RunProvenance) {}
    fun write(record: TransitionRecord)
    override fun close() {}
}

object NullSink : TransitionSink {
    override fun write(record: TransitionRecord) {}
}

class MemorySink : TransitionSink {
    private val rows = mutableListOf<TransitionRecord>()
    val records: List<TransitionRecord> get() = rows
    override fun write(record: TransitionRecord) { rows.add(record) }
}
