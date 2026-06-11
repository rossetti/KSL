package ksl.modeling.supplychain

import ksl.utilities.IdentityIfc

/**
 * Read-only view of a demand backlog: total units waiting and the
 * number of waiting demands.
 *
 * See `sc.inventorylayer.BackLogInfoIfc`
 */
interface BackLogInfoIfc : IdentityIfc {
    /** Total units associated with all backlogged demands. */
    val amountBackLogged: Int

    /** Total number of backlogged demands. */
    val numberOfDemandsBackLogged: Int
}
