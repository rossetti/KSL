package ksl.modeling.decision.descriptor

import kotlinx.serialization.Serializable

/**
 *  STUB — signatures only, per Appendix E.2 of the sequential decision making proposal.
 *  This package holds plain serializable data. It must not reference ksl.simulation.
 */

@Serializable
data class SchemaVersion(val major: Int = 1, val minor: Int = 0)

enum class LeverDomain { CONTINUOUS, INTEGER, CATEGORICAL }

enum class RewardKind { TIME_INTEGRAL, OBSERVATION_SUM, COUNTER_TOTAL }

enum class RewardSense { REWARD, COST }

enum class FeasibilityPolicy { REJECT, CLAMP_THEN_REJECT }

enum class TerminationSource { NATURAL, MAX_EPOCHS, RUN_LENGTH, MODEL_STOPPED, POLICY_ERROR }

enum class EpochKind { PERIODIC, CALENDAR }

@Serializable
sealed interface SourceRef {
    val name: String
}

@Serializable
data class ResponseRef(override val name: String) : SourceRef

@Serializable
data class CounterRef(override val name: String) : SourceRef

@Serializable
sealed interface JointConstraint {
    val names: List<String>
}

@Serializable
data class SumEquals(override val names: List<String>, val total: Double) : JointConstraint

@Serializable
data class SumAtMost(override val names: List<String>, val total: Double) : JointConstraint

@Serializable
data class ObservationDescriptor(
    val name: String,
    val domain: LeverDomain = LeverDomain.CONTINUOUS,
    val unit: String? = null
)

@Serializable
data class LeverDescriptor(
    val name: String,
    val domain: LeverDomain,
    val modelLowerLimit: Double,
    val modelUpperLimit: Double,
    val lowerBound: Double,
    val upperBound: Double,
    /**
     *  True when 𝒳(s) narrows the bounds above at each epoch (§4.4.6). The reported numbers
     *  are then an ENVELOPE, not the feasible set, and a consumer that cannot handle a
     *  state-dependent set should refuse rather than take them for the action space.
     */
    val stateDependent: Boolean = false,
    val levels: List<String>? = null,
    val unit: String? = null
)

@Serializable
data class RewardDescriptor(
    val name: String,
    val source: SourceRef,
    val kind: RewardKind,
    val rate: Double,
    val sense: RewardSense
)

@Serializable
data class EpochDescriptor(
    val kind: EpochKind,
    val interval: Double? = null,
    val calendar: List<Double>? = null,
    val firstAtTimeZero: Boolean = false,
    val priority: Int = 100_000
)

@Serializable
data class EpisodeDescriptor(
    val maxEpochs: Int = Int.MAX_VALUE,
    val hasTerminalCondition: Boolean = false
)

@Serializable
data class DecisionSurfaceDescriptor(
    val schemaVersion: SchemaVersion = SchemaVersion(),
    val name: String,
    val observations: List<ObservationDescriptor>,
    val levers: List<LeverDescriptor>,
    val constraints: List<JointConstraint> = emptyList(),
    val rewards: List<RewardDescriptor> = emptyList(),
    val epochs: EpochDescriptor,
    val episode: EpisodeDescriptor = EpisodeDescriptor(),
    val feasibility: FeasibilityPolicy = FeasibilityPolicy.REJECT
)

class SchemaVersionException(message: String) : RuntimeException(message)
