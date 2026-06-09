package ksl.modeling.supplychain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable description of an inventory replenishment policy on an
 * [InventorySpec].  Maps to the three concrete policy factory methods
 * on the framework's `Inventory`:
 *
 * | variant | framework factory |
 * |---|---|
 * | [SQ] | `addReorderPointReorderQuantityInventory(item, s, Q, initialOnHand)` |
 * | [SS] | `addReorderPointOrderUpToLevelInventory(item, s, S, initialOnHand)` |
 * | [SSPeriodic] | periodic `(s, S)` review policy |
 *
 * The builder (D2) maps each variant to its factory; `initialOnHand`
 * comes from the enclosing [InventorySpec].
 */
@Serializable
sealed class PolicySpec {

    /**
     * Continuous-review reorder-point / reorder-quantity `(s, Q)`:
     * when the inventory position drops to [reorderPoint] (`s`), order
     * [reorderQty] (`Q`) units.
     */
    @Serializable
    @SerialName("sQ")
    data class SQ(val reorderPoint: Int, val reorderQty: Int) : PolicySpec()

    /**
     * Continuous-review reorder-point / order-up-to-level `(s, S)`:
     * when the inventory position drops to [reorderPoint] (`s`), order
     * up to [orderUpToLevel] (`S`).
     */
    @Serializable
    @SerialName("sS")
    data class SS(val reorderPoint: Int, val orderUpToLevel: Int) : PolicySpec()

    /**
     * Periodic-review `(s, S)`: every [reviewInterval] the policy
     * checks the position and, if at or below [reorderPoint], orders up
     * to [orderUpToLevel].
     */
    @Serializable
    @SerialName("sSPeriodic")
    data class SSPeriodic(
        val reorderPoint: Int,
        val orderUpToLevel: Int,
        val reviewInterval: RVSpec,
    ) : PolicySpec()
}
