package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

/**
 * Thrown when no carrier option is available for transporting a demand.
 *
 * @see sc.transportlayer.NoCarrierOptionException
 */
class NoCarrierOptionException(
    message: String = "NoCarrierOptionException",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
