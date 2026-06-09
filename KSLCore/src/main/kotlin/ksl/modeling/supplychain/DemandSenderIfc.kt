package ksl.modeling.supplychain

import ksl.utilities.IdentityIfc

/**
 * Objects that send demand requests to a [DemandFillerIfc].
 *
 * @see sc.inventorylayer.DemandSenderIfc
 */
interface DemandSenderIfc : IdentityIfc {
    /** True if this sender may produce demands of [type]. */
    fun mightRequest(type: ItemType): Boolean

    /** Finder used to locate a filler for each generated demand. */
    var demandFillerFinder: DemandFillerFinderIfc?

    /** Direct filler override; if set, used instead of [demandFillerFinder]. */
    var demandFiller: DemandFillerIfc?
}
