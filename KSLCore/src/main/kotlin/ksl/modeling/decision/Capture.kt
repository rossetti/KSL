package ksl.modeling.decision

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor

/**
 *  STUB — Appendix E.2, §4.8.2. The capture CONTRACT lives with the element that produces
 *  records; ksl.sdm.capture provides implementations. Dependency inversion: without it the
 *  two packages would import each other and E.1's one-way layering would be a fiction.
 */

data class RunProvenance(
    val modelName: String,
    val experimentName: String,
    val elementName: String,
    val policyLabel: String,
    val descriptor: DecisionSurfaceDescriptor
)

interface TransitionSink : AutoCloseable {
    fun open(provenance: RunProvenance) {}
    fun write(record: TransitionRecord)
    override fun close() {}
}

