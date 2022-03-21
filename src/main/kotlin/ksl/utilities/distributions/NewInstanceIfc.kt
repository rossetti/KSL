package ksl.utilities.distributions

/**
 *  An interface that promises to supply a new
 *  instance of the class that implements it via the instance() method
 */
fun interface NewInstanceIfc<out T> {

    /**
     *  General method for getting a new instance of the
     *  class that implements this interface
     *  @return the new instance
     */
    fun instance(): T
}