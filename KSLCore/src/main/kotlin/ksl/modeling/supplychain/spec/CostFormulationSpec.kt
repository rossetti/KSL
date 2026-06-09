package ksl.modeling.supplychain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable twin of the framework's `CostParams`.  Kept separate
 * from the runtime type so the runtime `CostParams` stays free of
 * serialization annotations.  The builder (D5) constructs a
 * `CostParams` from these fields.
 *
 * Field meanings (and the time-unit convention for the two rates) are
 * documented on the runtime `CostParams`: continuous rates are
 * denominated in 1 / (modeler-chosen time unit); the framework does no
 * time conversion.
 */
@Serializable
data class CostParamsSpec(
    val carryingRate: Double = 0.10,
    val backorderRate: Double = 0.0,
    val orderingCost: Double = 5.5,
    val unloadingCost: Double = 30.0,
    val loadingCost: Double = 40.0,
    val shippingCost: Double = 15.0,
    val stockoutCost: Double = 0.0,
    val lostSaleCost: Double = 0.0,
    val unitShortageCost: Double = 0.0,
    val esLoadingCost: Double = 40.0,
)

/**
 * Serializable description of a cost formulation to attach to the
 * network.  A [NetworkSpec] may carry several (a comparative study on
 * one simulation — see the cost-redesign doc's multi-attach support).
 * The builder constructs and attaches each one **last**, after the
 * topology is final, satisfying the cost-formulation ordering and
 * coverage guards.
 *
 * v1 variants:
 * - [Default] — one [CostParamsSpec] applied network-wide
 *   (`DefaultMultiEchelonCostFormulation`).
 * - [PerNodeIHP] — a default [CostParamsSpec] with per-node overrides
 *   keyed by node name.  Requires a small new framework type, landing
 *   with the cost-spec phase (D5).
 *
 * Custom user formulations are a v1 escape hatch (attach in Kotlin
 * after building).
 */
@Serializable
sealed class CostFormulationSpec {

    /** Optional report-name prefix, distinguishing formulations in the report. */
    abstract val name: String?

    /** Uniform-parameters formulation applied across the whole network. */
    @Serializable
    @SerialName("default")
    data class Default(
        override val name: String? = null,
        val params: CostParamsSpec = CostParamsSpec(),
    ) : CostFormulationSpec()

    /**
     * Per-node-override formulation: a [default] parameter set plus
     * [overrides] keyed by node name.  Nodes not in [overrides] use
     * [default].
     */
    @Serializable
    @SerialName("perNodeIHP")
    data class PerNodeIHP(
        override val name: String? = null,
        val default: CostParamsSpec = CostParamsSpec(),
        val overrides: Map<String, CostParamsSpec> = emptyMap(),
    ) : CostFormulationSpec()
}
