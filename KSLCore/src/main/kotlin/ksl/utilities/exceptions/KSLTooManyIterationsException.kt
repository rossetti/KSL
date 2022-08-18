package ksl.utilities.exceptions

class KSLTooManyIterationsException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    constructor(cause: Throwable) : this(null, cause)
}