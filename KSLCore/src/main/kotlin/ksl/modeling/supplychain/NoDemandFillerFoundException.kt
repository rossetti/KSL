package ksl.modeling.supplychain

/**
 * Thrown when no [DemandFillerIfc] is available to fill a given demand.
 *
 * @see sc.inventorylayer.NoDemandFillerFoundException
 */
class NoDemandFillerFoundException(
    message: String = "NoDemandFillerFoundException",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
