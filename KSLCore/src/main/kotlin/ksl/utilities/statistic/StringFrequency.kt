package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.io.dbutil.DbTableData
import ksl.utilities.io.plotting.IntegerFrequencyPlot
import ksl.utilities.io.plotting.StringFrequencyPlot
import ksl.utilities.random.robj.DEmpiricalList
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.toDoubles
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 * This class tabulates the frequency associated with
 * the strings presented to it via the collect() method.
 * For every unique string presented a count is maintained.
 * There could be space/time performance issues if
 * the number of different strings presented is large.
 * Use the limit set to limit the values
 * that can be observed. If the presented strings are
 * not in the limiting set, then they are counted as "Other".
 *
 * This class can be useful for tabulating a
 * discrete histogram over the values (strings) presented.
 *
 * @author rossetti
 *
 * @param name a name for the instance
 * @param data an array of data to tabulate
 * @param limitSet a set of strings that limit what is to be counted. If null, there
 * is no limit. That is, every unique string presented is tabulated.
 */
class StringFrequency(
    data: Collection<String>? = null,
    name: String? = null,
    val limitSet: Set<String>? = null
) : IdentityIfc by Identity(name){

    /**
     * A Cell represents a value, count pairing
     */
    private val myCells: MutableMap<String, Cell> = HashMap()

    var otherCount: Int = 0
        private set

    var totalCount: Int = 0
        private set

    init {
        if (data != null) {
            collect(data)
        }
    }

    /**
     * @param strings tabulates frequencies for the unique strings in the array
     */
    fun collect(strings: Array<String>) {
        for (string in strings) {
            collect(string)
        }
    }

    /**
     * @param strings tabulates frequencies for the unique strings in the array
     */
    fun collect(strings: Collection<String>) {
        for (string in strings) {
            collect(string)
        }
    }

    /**
     * Tabulates the count of the number of unique strings
     * presented.
     *
     * @param string the presented string
     */
    fun collect(string: String) {
        if (limitSet != null) {
            if (!limitSet.contains(string)) {
                otherCount += 1
            }
            return
        }
        totalCount = totalCount + 1
        val c = myCells[string]
        if (c == null) {
            myCells[string] = Cell(string)
        } else {
            c.increment()
        }
    }

    /**
     * Resets the statistical collection
     */
    fun reset() {
        otherCount = 0
        totalCount = 0
        myCells.clear()
    }

    /**
     * Returns a list of size numberOfCells containing
     * the observed strings in the order in which they were observed. The 0th element
     * of the array contains the first string observed, 1st element
     * the next unique string, etc.
     *
     * @return the array of strings observed or an empty array
     */
    val values: List<String>
        get() {
            if (myCells.isEmpty()) {
                return emptyList()
            }
            return myCells.keys.toList()
        }

    /**
     * Returns an array of size numberOfCells containing
     * the frequencies for each string observed. The 0th element
     * is the frequency for the string stored at element 0 of the
     * array returned by the values property
     *
     * @return the array of frequencies observed or an empty array
     */
    val frequencies: IntArray
        get() {
            if (myCells.isEmpty()) {
                return IntArray(0)
            }
            val list = mutableListOf<Int>()
            for ((string, cell) in myCells) {
                list.add(cell.count.toInt())
            }
            return list.toIntArray()
        }

    /**
     * Returns an array of size numberOfCells containing
     * the proportion by value. The 0th element
     * is the proportion for the value stored at element 0 of the
     * array returned by the values property, etc.
     *
     * @return the array of proportions observed or an empty array
     */
    val proportions: DoubleArray
        get() {
            if (myCells.isEmpty()) {
                return DoubleArray(0)
            }
            val list = mutableListOf<Double>()
            for ((string, cell) in myCells) {
                list.add(cell.proportion)
            }
            return list.toDoubleArray()
        }

    /**
     * Returns the cumulative frequency up to an including the string
     *
     * @param string the string for the desired frequency
     * @return the cumulative frequency
     */
    fun cumulativeFrequency(string: String): Double {
        if (myCells.isEmpty()) {
            return 0.0
        }
        if (!myCells.containsKey(string)) {
            return 0.0
        }
        // the string is in the cells
        var sum = 0.0
        for ((str, cell) in myCells) {
            sum = sum + cell.count
            if (string == str) {
                break
            }
        }
        return sum
    }

    /**
     * Returns the cumulative proportion up to an including the
     * supplied string
     *
     * @param string the string for the desired proportion
     * @return the cumulative proportion
     */
    fun cumulativeProportion(string: String): Double {
        if (myCells.isEmpty()) {
            return 0.0
        }
        if (!myCells.containsKey(string)) {
            return 0.0
        }
        return cumulativeFrequency(string) / totalCount
    }

    /**
     * Returns Map holding the observed strings and frequencies within the map
     *
     * @return the Map
     */
    val stringFrequencies: Map<String, Int>
        get() {
            if (myCells.isEmpty()) {
                return emptyMap()
            }
            val map = mutableMapOf<String, Int>()
            for ((string, cell) in myCells) {
                map[string] = cell.count.toInt()
            }
            return map
        }

    /**
     * Returns Map holding the strings and associated proportions.
     *
     * @return the Map
     */
    val stringProportions: Map<String, Double>
        get() {
            if (myCells.isEmpty()) {
                return emptyMap()
            }
            val map = mutableMapOf<String, Double>()
            for ((string, cell) in myCells) {
                map[string] = cell.proportion
            }
            return map
        }

    /**
     * Returns Map holding the string and cumulative proportions as elements
     * in the map
     *
     * @return the Map
     */
    val stringCumulativeFrequencies: Map<String, Int>
        get() {
            if (myCells.isEmpty()) {
                return emptyMap()
            }
            val map = mutableMapOf<String, Int>()
            var sum = 0
            for ((string, cell) in myCells) {
                sum = sum + cell.count.toInt()
                map[string] = sum
            }
            return map
        }

    /**
     * Returns the number of cells tabulated
     * This is also the total number of different strings observed
     *
     * @return the number of cells tabulated
     */
    val numberOfStrings: Int
        get() = myCells.size

    /**
     * Returns the current frequency for the provided string
     *
     * @param string the provided string
     * @return the frequency
     */
    fun frequency(string: String): Double {
        val c = myCells[string]
        return c?.count ?: 0.0
    }

    /**
     * Gets the proportion of the observations that
     * are equal to the supplied string
     *
     * @param string the string
     * @return the proportion
     */
    fun proportion(string: String): Double {
        val c = myCells[string]
        return c?.proportion ?: 0.0
    }

    /**
     * Interprets the elements of x[] as values
     * and returns an array representing the frequency
     * for each value
     *
     * @param x the values for the frequencies
     * @return the returned frequencies
     */
    fun frequencies(x: Array<String>): DoubleArray {
        val f = DoubleArray(x.size)
        for (j in x.indices) {
            f[j] = frequency(x[j])
        }
        return f
    }

    /**
     * Returns a copy of the cells in a list.
     *
     * @return the list
     */
    fun cellList(): List<Cell> {
        val list = mutableListOf<Cell>()
        for ((_, cell) in myCells) {
            list.add(cell.instance())
        }
        return list
    }

    /**
     * Returns a copy of the cells in a list
     * ordered by the count of each cell, 0th element
     * is cell with the largest count, etc
     *
     * @return the list
     */
    fun cellsSortedByCount(): List<Cell> {
        val cells = cellList()
        return cells.sortedByDescending { it.count }
    }

    /**
     *  Returns the data associated with the tabulation.
     */
    fun frequencyData(sortedByCount: Boolean = false): List<StringFrequencyData> {
        val list = mutableListOf<StringFrequencyData>()
        val cells = if (sortedByCount) {
            myCells.toList().sortedByDescending { (_, cell) -> cell.count }.toMap()
        } else {
            myCells
        }
        var cp = 0.0
        var ct = 0.0
        for ((string, cell) in cells) {
            cp = cp + cell.proportion
            ct = ct + cell.count
            list.add(StringFrequencyData(id, name, string, cell.count, ct, cell.proportion, cp))
        }
        return list
    }

    /**
     * @return a DEmpiricalList based on the frequencies
     */
    fun createDEmpiricalList(): DEmpiricalList<String> {
        return DEmpiricalList(values, KSLRandom.makeCDF(proportions))
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Frequency Tabulation ").append(name).appendLine()
        sb.append("----------------------------------------").appendLine()
        sb.append("Number of cells = ").append(numberOfStrings).appendLine()
        sb.append("Total count = ").append(totalCount).appendLine()
        sb.append("----------------------------------------").appendLine()
        sb.append("Value \t Count \t Proportion\n")
        for ((_, cell) in myCells) {
            sb.append(cell).appendLine()
        }
        sb.append("----------------------------------------").appendLine()
        return sb.toString()
    }

    /**
     *  Creates a plot for the integer frequencies. The parameter, [proportions]
     *  indicates whether proportions (true) or frequencies (false)
     *  will be shown on the plot. The default is false.
     */
    fun frequencyPlot(proportions: Boolean = false): StringFrequencyPlot {
        return StringFrequencyPlot(this, proportions)
    }

    /**
     * Holds the values and their counts
     */
    inner class Cell(val string: String) : Comparable<Cell> {

        var count = 1.0
            private set

        val proportion: Double
            get() = count / totalCount

        override operator fun compareTo(other: Cell): Int {
            return string.compareTo(other.string)
        }

        fun increment() {
            count++
        }

        override fun toString(): String {
            return "$string \t $count  \t $proportion"
        }

        fun instance(): Cell {
            val c = Cell(string)
            c.count = count
            return c
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Cell

            if (string != other.string) return false

            return true
        }

        override fun hashCode(): Int {
            return string.hashCode()
        }
    }

}

/**
 *  A data class holding the summary frequency data
 */
data class StringFrequencyData(
    var id: Int = 1,
    var name: String = "",
    var string: String = "",
    var count: Double = 0.0,
    var cum_count: Double = 0.0,
    var proportion: Double = 0.0,
    var cum_proportion: Double = 0.0
) {
    fun asStringFrequencyRecord(): StringFrequencyRecord {
        return StringFrequencyRecord(id, name, string, count, proportion, cum_proportion)
    }
}

/**
 *  A data table class suitable for insertion into a database
 */
data class StringFrequencyRecord(
    var id: Int = 1,
    var name: String = "",
    var string: String = "",
    var count: Double = 0.0,
    var cum_count: Double = 0.0,
    var proportion: Double = 0.0,
    var cum_proportion: Double = 0.0
) : DbTableData("tblStringFrequency", listOf("id","string"))

fun main() {
    val possibilities = listOf("TP", "FP", "FN", "TN")
    val rList = DEmpiricalList<String>(possibilities, doubleArrayOf(0.20, 0.7, 0.8, 1.0 ))
    val data = rList.sample(100)
    println(data.joinToString())
    val sf = StringFrequency(data = data)
    println(sf)
//    sf.frequencyPlot().showInBrowser()
    println(sf.frequencyData().toDataFrame())
}