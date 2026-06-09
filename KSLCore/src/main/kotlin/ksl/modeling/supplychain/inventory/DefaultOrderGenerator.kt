package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Convenience subclass of [OrderGenerator] that bundles a built-in
 * [RandomOrderCreator]. Item types are registered via the generator's
 * own [addItemTypeDistribution] rather than configuring the creator
 * separately.
 *
 * @see sc.inventorylayer.DefaultOrderGenerator
 */
open class DefaultOrderGenerator @JvmOverloads constructor(
    supplyChainModel: SupplyChainModel,
    timeUntilFirstRV: RVariableIfc,
    timeBtwEventsRV: RVariableIfc,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null,
) : OrderGenerator(
    supplyChainModel, null, timeUntilFirstRV, timeBtwEventsRV,
    maxNumberOfEvents, timeOfTheLastEvent, name,
) {
    private val builtInCreator: RandomOrderCreator =
        RandomOrderCreator(supplyChainModel, "${this.name}:Creator")

    init { orderCreator = builtInCreator }

    /** The built-in [RandomOrderCreator]. */
    val randomOrderCreator: RandomOrderCreator get() = builtInCreator

    /** Delegates to [randomOrderCreator]. */
    @JvmOverloads
    fun addItemTypeDistribution(
        type: ItemType,
        includeDistribution: RVariableIfc = ConstantRV.ONE,
        amountDistribution: RVariableIfc = ConstantRV.ONE,
    ) {
        builtInCreator.addItemTypeDistribution(
            type, includeDistribution, amountDistribution,
        )
    }
}
