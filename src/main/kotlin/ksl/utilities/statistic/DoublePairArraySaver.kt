package ksl.utilities.statistic

import ksl.utilities.copyOf
import ksl.utilities.observers.ObservableIfc
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.toCSVString
import java.io.PrintWriter

class DoublePairArraySaver : ObserverIfc<Pair<Double, Double>> {

    /**
     * The default increment for the array size
     *
     */
    var arraySizeIncrement = 1000
        set(value) {
            require(value > 0) { "Default array growth size must be > 0" }
            field = value
        }

    /**
     * A flag to indicate whether the saver should save the data as
     * it is collected.  If this flag is true, the data will be saved
     * when the save() method is called.
     */
    var saveOption: Boolean = true

    /**
     * The array to collect the data if the saved flag is true
     * Uses lazy initialization. Doesn't allocate array until
     * save is attempted and save option is on.
     */
    private var myData: Array<DoubleArray> = Array(arraySizeIncrement) { DoubleArray(2) }

    /**
     * Counts the number of data points that were saved to the save array
     */
    var saveCount = 0
        private set

    /**
     *  Clears any data that has been saved
     */
    fun clearData() {
        myData = Array(arraySizeIncrement) { DoubleArray(2) }
        saveCount = 0
    }

    /**
     * @return the data that has been saved
     */
    fun savedData(): Array<DoubleArray>  {
        if (saveCount == 0) {
            return emptyArray()
        }
        return myData.copyOf()
    }

    /**
     * @param first, the 1st of the pair
     * @param second, the 2nd of the pair
     */
    fun save(first: Double, second: Double) {
        if (!saveOption) {
            return
        }
        // need to save x into the array
        saveCount++
        if (saveCount > myData.size) {
            // need to grow the array
            myData = Array(arraySizeIncrement) { DoubleArray(2) }
        }
        myData[saveCount - 1][0] = first
        myData[saveCount - 1][1] = second
    }

    override fun update(theObserved: ObservableIfc<Pair<Double, Double>>, newValue: Pair<Double, Double>?) {
        if (newValue != null) {
            save(newValue.first, newValue.second)
        }
    }

    /** Writes out the saved data to a file.
     *
     * @param out the place to write the data
     */
    fun write(out: PrintWriter) {
        var i = 1
        for (x in myData) {
            //out.println("$i, $x")
            out.println(x.toCSVString())
            if (i == saveCount) {
                out.flush()
                return
            }
            i++
        }
        out.flush()
    }
}