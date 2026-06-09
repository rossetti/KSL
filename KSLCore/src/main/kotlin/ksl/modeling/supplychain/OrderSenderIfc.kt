package ksl.modeling.supplychain

import ksl.utilities.IdentityIfc

/**
 * Objects that send orders to an [OrderFillerIfc].
 *
 * @see sc.inventorylayer.OrderSenderIfc
 */
interface OrderSenderIfc : IdentityIfc {
    /** True if this sender may produce orders containing [type]. */
    fun mightRequest(type: ItemType): Boolean
}
