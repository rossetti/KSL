package ksl.utilities.io

/**
 * Detects the number of CPU cores available to the currently executing program
 */
object CpuCoreDetector {

    /**
     * Returns the number of processors available to the JVM
     *
     * This value may change during a particular invocation of the virtual machine.
     * Applications that are sensitive to the number of available processors should
     * therefore occasionally poll this property.
     *
     * @return The number of available processors
     */
    fun getAvailableCores(): Int {
        return Runtime.getRuntime().availableProcessors()
    }

    /**
     * Checks if the system has enough cores for parallel processing
     */
    fun hasMultipleCores(): Boolean {
        return getAvailableCores() > 1
    }

    /**
     * Returns optimal thread pool size based on available cores
     * Common formula: cores * 2 for I/O bound tasks, cores for CPU bound tasks
     */
    fun getOptimalThreadPoolSize(ioBound: Boolean = false): Int {
        val cores = getAvailableCores()
        return if (ioBound) cores * 2 else cores
    }
}