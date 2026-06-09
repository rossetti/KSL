/*
 * Internal helpers for walking the model-element tree to recover
 * the enclosing [SupplyChainModel]. Used by classes that need to
 * create transient supply-chain entities (demands, shipments) but
 * are nested under intermediate model elements.
 *
 * Lives in the supplychain root package because it accesses
 * `internal myParentModelElement` on [ModelElement]; KSL's
 * `internal` is module-scoped, so any file in KSLCore can reach
 * the field.
 *
 * Previously this walk was duplicated as a private method on
 * `Inventory` and `InventoryCrossDock`; both will be migrated to
 * this shared helper as a follow-up.
 */
package ksl.modeling.supplychain

import ksl.simulation.ModelElement

/**
 * Walks the parent chain from `this` model element upward until it
 * finds a [SupplyChainModel]. Throws if no [SupplyChainModel]
 * ancestor exists.
 *
 * @throws IllegalStateException if `this` is not parented under a
 *         [SupplyChainModel]
 */
internal fun ModelElement.findEnclosingSupplyChainModel(): SupplyChainModel {
    // Use `internal myParentModelElement` rather than the
    // `protected val parent`, because Kotlin's `protected` is
    // class-scoped and we walk through other ModelElement
    // instances en route.
    var p: ModelElement? = this.myParentModelElement
    while (p != null) {
        if (p is SupplyChainModel) return p
        p = p.myParentModelElement
    }
    error("$name is not parented under a SupplyChainModel")
}
