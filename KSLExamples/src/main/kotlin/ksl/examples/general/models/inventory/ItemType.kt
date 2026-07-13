package ksl.examples.general.models.inventory

import ksl.utilities.Identity

/**
 * A stock-keeping item type carried by the inventory models — an [Identity] (unique name/id) with a
 * unit cost and physical weight and cube (volume) attributes. Every [DemandCreator.Demand] and
 * [Inventory] is for a specific item type.
 */
class ItemType(
    name: String? = null,
) : Identity(name) {

    var unitCost: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The unit cost must be >= 0.0" }
            field = value
        }
    var weight: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The weight must be >= 0.0" }
            field = value
        }
    var cube: Double = 1.0
        set(value) {
            require(value >= 0.0) { "The cube must be >= 0.0" }
            field = value
        }
}