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

/**
 *  §4.8.2. A write-only consumer with a lifetime.
 *
 *  There is no `open(provenance)`. Provenance reaches a sink through the **factory** —
 *  `captureTo { provenance -> sink }`, called once per element at `beforeExperiment()` — which is
 *  the mechanism the element actually uses. An `open` was declared alongside it with an empty
 *  default body and was never called by anything, and one job with two mechanisms, one of them
 *  unreachable, is how the next implementer fills in the dead one.
 */
interface TransitionSink : AutoCloseable {
    fun write(record: TransitionRecord)
    override fun close() {}
}

