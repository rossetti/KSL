package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

/**
 * Thrown when an inventory has no replenishment option available.
 *
 * @see sc.inventorylayer.NoReplenishmentOptionException
 */
class NoReplenishmentOptionException(
    message: String = "NoReplenishmentOptionException",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
