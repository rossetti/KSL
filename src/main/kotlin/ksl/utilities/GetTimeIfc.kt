package ksl.utilities

/**
 * An interface to define a method to get simulated time as a double
 */
fun interface GetTimeIfc {

    //TODO is this okay?
//    val time: Double
//        get() = time()

    /**
     *
     * @return the simulated time as a double
     */
    fun time(): Double
}