package ksl.utilities

fun interface GetValueIfc {

    val value: Double
        get() = value()

    /** This method simply returns the value.
     * @return The value.
     */
    fun value(): Double
}