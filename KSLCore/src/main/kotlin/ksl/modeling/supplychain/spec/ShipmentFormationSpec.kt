package ksl.modeling.supplychain.spec

import kotlinx.serialization.Serializable

/**
 * Per-edge shipment-formation forming option, mirroring the framework's
 * `DemandLoadBuilder.LoadFormingOption` — minus `RULE`, which carries a
 * user-supplied function and cannot be serialized (use the v1 escape
 * hatch: build the spec, then install the rule in Kotlin).
 */
enum class FormingOption { NONE, ALWAYS, COUNT, WEIGHT, CUBE }

/**
 * A `[min, max]` limit pair for [FormingOption.WEIGHT] /
 * [FormingOption.CUBE] formation.  A small data class rather than a
 * `Pair` so it serializes cleanly to TOML and JSON.
 */
@Serializable
data class LimitsSpec(val min: Double, val max: Double)

/**
 * Serializable description of a per-edge shipment-formation policy,
 * mirroring the framework's `ShipmentFormation`.  Attached to a
 * [NodeSpec] ([NodeSpec.shipmentFormationFromParent]) or a
 * [DemandGeneratorSpec] ([DemandGeneratorSpec.shipmentFormation]).
 *
 * Validation (see [validate]):
 * - [FormingOption.COUNT] requires [countLimit] `> 0`.
 * - [FormingOption.WEIGHT] requires [weightLimits].
 * - [FormingOption.CUBE] requires [cubeLimits].
 * - Formation is only permitted under
 *   [TransportStrategySpec.PerIHPTimeBased].
 *
 * @param option the forming trigger
 * @param countLimit demands per load under [FormingOption.COUNT]
 * @param weightLimits min/max queued weight under [FormingOption.WEIGHT]
 * @param cubeLimits min/max queued cube under [FormingOption.CUBE]
 */
@Serializable
data class ShipmentFormationSpec(
    val option: FormingOption,
    val countLimit: Int? = null,
    val weightLimits: LimitsSpec? = null,
    val cubeLimits: LimitsSpec? = null,
)
