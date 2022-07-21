package ksl.utilities

/**
 *  Permits implementers to indicate that they can create a new instance.
 *  The created instance should be a duplicate of the object instance in
 *  all relevant fields to achieve the same functionality.
 *
 */
fun interface NewInstanceIfc<out T> {
    /**
     * Returns a new instance
     *
     * @return the new instance
     */
    fun instance(): T
}