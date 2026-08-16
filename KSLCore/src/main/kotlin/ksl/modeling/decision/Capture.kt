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

