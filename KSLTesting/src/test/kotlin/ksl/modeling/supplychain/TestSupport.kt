package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

/**
 * Test helper: a no-op [DemandFillerIfc] implementation that satisfies
 * the full interface. Tests subclass and override only the methods they
 * exercise; methods that should not be reached call [notUsed].
 */
internal open class NoOpDemandFiller(
    override val name: String = "NoOpDemandFiller",
    override val id: Int = 0,
    override var label: String? = null,
    override val isAvailable: Boolean = true,
) : DemandFillerIfc {
    override fun receive(demand: SupplyChainModel.Demand) = notUsed("receive")
    override fun fillDemand(demand: SupplyChainModel.Demand) = notUsed("fillDemand")
    override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? =
        notUsed("negotiate")
    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean =
        notUsed("canFillItemType(Demand)")
    override fun canFillItemType(type: ItemType): Boolean =
        notUsed("canFillItemType(ItemType)")
    override val itemTypes: Collection<ItemType> = emptyList()
    override fun determineRequestStatus(demand: SupplyChainModel.Demand): DemandStatusCode =
        notUsed("determineRequestStatus")
    override fun willReject(demand: SupplyChainModel.Demand): Boolean =
        notUsed("willReject")
    override var demandCarrier: DemandCarrierIfc? = null
    override var demandPreparer: DemandPreparerIfc? = null

    protected fun notUsed(method: String): Nothing =
        error("test stub: $method not implemented")
}

/**
 * Test helper: a no-op [OrderFillerIfc] implementation. Same idea as
 * [NoOpDemandFiller].
 */
internal open class NoOpOrderFiller(
    override val name: String = "NoOpOrderFiller",
    override val id: Int = 0,
    override var label: String? = null,
    override val isAvailable: Boolean = true,
) : OrderFillerIfc {
    override fun receive(order: SupplyChainModel.Order) = notUsed("receive")
    override fun fill(order: SupplyChainModel.Order) = notUsed("fill")
    override fun canFillItemTypes(order: SupplyChainModel.Order): Boolean =
        notUsed("canFillItemTypes")
    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean =
        notUsed("canFillItemType")
    override fun negotiate(order: SupplyChainModel.Order): OrderMessageIfc? =
        notUsed("negotiate")

    protected fun notUsed(method: String): Nothing =
        error("test stub: $method not implemented")
}
