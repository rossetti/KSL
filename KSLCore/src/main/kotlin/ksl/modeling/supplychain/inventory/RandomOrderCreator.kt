package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * [OrderCreatorIfc] that builds orders by stochastically including a
 * subset of registered item types. Each item type has an associated
 * probability of being included on a given order and a distribution
 * for the order quantity.
 *
 * @param supplyChainModel the supply-chain model whose `createOrder` /
 *        `createDemand` factories are used
 * @param name optional model-element name
 *
 * See `sc.inventorylayer.RandomOrderCreator`
 */
open class RandomOrderCreator @JvmOverloads constructor(
    val supplyChainModel: SupplyChainModel,
    name: String? = null,
) : ModelElement(supplyChainModel, name), OrderCreatorIfc {

    /** Whether the orders created allow backlogging. */
    var permitBackLogging: Boolean = true

    private data class ItemTypeInfo(
        val includeRV: RandomVariable,
        val amountRV: RandomVariable,
    )

    private val myItemTypes: MutableMap<ItemType, ItemTypeInfo> = linkedMapOf()

    /**
     * Register an item type. The order creator will include [type] on
     * each order when [includeDistribution] draws a positive value
     * (`> 0.0`); when included, the quantity is sampled from
     * [amountDistribution].
     *
     * Both distributions carry their own stream numbers — for
     * probabilistic inclusion, pass e.g. `BernoulliRV(p, streamNum)`.
     * For deterministic "always include", pass [ConstantRV.ONE] (the
     * default). For "never include", pass [ConstantRV.ZERO].
     *
     * The probability-as-Double overload was dropped because KSL's
     * `BernoulliRV` rejects 0.0 and 1.0; forcing a degenerate Bernoulli
     * would lose those edge cases.
     *
     * @param type the item type to register
     * @param includeDistribution include-decision distribution; defaults
     *        to a constant 1.0 (always include)
     * @param amountDistribution distribution of order quantity; defaults
     *        to a constant 1.0
     */
    @JvmOverloads
    fun addItemTypeDistribution(
        type: ItemType,
        includeDistribution: RVariableIfc = ConstantRV.ONE,
        amountDistribution: RVariableIfc = ConstantRV.ONE,
    ) {
        require(type !in myItemTypes) { "item type already registered" }
        val info = ItemTypeInfo(
            includeRV = RandomVariable(this, includeDistribution,
                name = "${this.name} : ${type.name} : Include"),
            amountRV = RandomVariable(this, amountDistribution,
                name = "${this.name} : ${type.name} : Amount"),
        )
        myItemTypes[type] = info
    }

    override fun createsType(type: ItemType): Boolean = type in myItemTypes

    override fun createOrder(): SupplyChainModel.Order? {
        var order: SupplyChainModel.Order? = null
        for ((type, info) in myItemTypes) {
            if (info.includeRV.value > 0.0) {
                if (order == null) {
                    order = supplyChainModel.createOrder(
                        allowBackLogging = permitBackLogging,
                    )
                }
                val amount = info.amountRV.value.toInt()
                if (amount > 0) {
                    val d = supplyChainModel.createDemand(type, amount)
                    d.setAllowBackLogging(permitBackLogging)
                    order.addDemand(d)
                }
            }
        }
        return order
    }
}
