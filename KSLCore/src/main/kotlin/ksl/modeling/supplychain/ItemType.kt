package ksl.modeling.supplychain

import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Models a stock-keeping unit (SKU) within an inventory system. An
 * [ItemType] is associated with a unit cost, a weight, a cube
 * (length × width × height), and optionally a [leadTime] distribution
 * used when this item type is stocked at a location with non-zero
 * replenishment lead time.
 *
 * The caller is responsible for stream-number assignment when
 * constructing the [leadTime] source; [ItemType] does not draw values
 * from it directly.
 *
 * @param parent the parent model element
 * @param name an optional name; defaults to a generated unique name
 * @param weight the weight per unit, must be > 0; defaults to 1.0
 * @param cube the cube per unit, must be >= 0; defaults to 1.0
 * @param leadTime an optional replenishment lead-time source; may be null
 * See `sc.inventorylayer.ItemType`
 */
class ItemType @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
    weight: Double = 1.0,
    cube: Double = 1.0,
    leadTime: RVariableIfc? = null,
) : ModelElement(parent, name) {

    init {
        require(weight > 0.0) { "weight must be > 0" }
        require(cube >= 0.0) { "cube must be >= 0" }
    }

    /** Unit cost; must be >= 0. Defaults to 1.0. */
    var unitCost: Double = 1.0
        set(value) {
            require(value >= 0.0) { "unit cost must be >= 0" }
            field = value
        }

    /** Weight per unit; must be > 0. */
    var weight: Double = weight
        private set(value) {
            require(value > 0.0) { "weight must be > 0" }
            field = value
        }

    /** Cube per unit; must be >= 0. */
    var cube: Double = cube
        private set(value) {
            require(value >= 0.0) { "cube must be >= 0" }
            field = value
        }

    /** Optional replenishment lead-time source. */
    var leadTime: RVariableIfc? = leadTime
        private set

    override fun toString(): String = name

    override fun removedFromModel() {
        super.removedFromModel()
        leadTime = null
    }
}
