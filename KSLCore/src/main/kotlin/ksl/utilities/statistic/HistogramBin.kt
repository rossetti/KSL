package ksl.utilities.statistic

/**
 * @param theBinNumber the bin number
 * @param theLowerLimit the lower limit of the bin
 * @param theUpperLimit the upper limit of the bin
 */
class HistogramBin(theBinNumber: Int, theLowerLimit: Double, theUpperLimit: Double) {

    init {
        require(theLowerLimit < theUpperLimit) { "The lower limit of the bin must be < the upper limit" }
        require(theBinNumber > 0) { "The bin number must be greater than 0" }
    }

    val lowerLimit: Double = theLowerLimit
    val upperLimit: Double = theUpperLimit

    private var count = 0
    /**
     * @return the label for the bin
     */
    /**
     * @param label The label for the bin
     */
    var binLabel: String = String.format("%3d [%5.2f,%5.2f) ", theBinNumber, theLowerLimit, theUpperLimit)

    /**
     * Gets the number of the bin 1 = first bin, 2 = 2nd bin, etc
     *
     * @return the number of the bin
     */
    val binNumber: Int = theBinNumber

    /**
     * @return an copy of this bin
     */
    fun instance(): HistogramBin {
        val bin = HistogramBin(binNumber, lowerLimit, upperLimit)
        bin.count = count
        return bin
    }

    /**
     * Increments the bin count by 1.0
     */
    fun increment() {
        count = count + 1
    }

    /**
     * Resets the bin count to 0.0
     */
    fun reset() {
        count = 0
    }

    fun count(): Double {
        return count.toDouble()
    }

    override fun toString(): String {
        // String s = "[" + lowerLimit + "," + upperLimit + ") = " + count;
        return String.format("%s = %d", binLabel, count)
    }
}