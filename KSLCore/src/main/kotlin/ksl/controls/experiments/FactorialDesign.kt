package ksl.controls.experiments

import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import kotlin.math.min

/**
 *  The number of elements that would exist in the
 *  cartesian product of the factors in the set
 */
fun Set<Factor>.cartesianProductSize(): Int {
    var n = 1
    for (factor in this) {
        n = n * factor.levels.size
    }
    return n
}

class DesignPoint internal constructor(
    val number: Int,
    val settings: Map<Factor, Double>
)

interface FactorialDesignIfc {
    val factors: Map<String, Factor>
    val numDesignPoints: Int
    val factorNames: List<String>

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0th element represents the first factor in the list of factor names.
     */
    fun designPointsToList(): List<DoubleArray>

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0th element represents the first factor in the list of factor names.
     */
    fun codedDesignPointsToList(): List<DoubleArray>

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels. The returned
     *  map holds pairs (factor name, level) for each of the factor settings
     *  at the designated design point.
     *
     *  @param k must be in 1 to numDesignPoints
     */
    fun designPointToMap(k: Int): Map<String, Double>

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *
     *  @param k must be in 1 to numDesignPoints
     *  @return the returned DesignPoint
     */
    fun designPoint(k: Int): DesignPoint

    /**
     *  Returns the name of the factor. The first factor is at k = 1
     *  @param k must be in 1 to number of factors
     */
    fun factorName(k: Int): String = factorNames[k - 1]

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels
     *  as a 2D array.  The rows of the array are the design points.
     *  The row array's 0th element represents the first factor in the list of factor names.
     */
    fun designPointsTo2DArray(): Array<DoubleArray> {
        return KSLArrays.to2DDoubleArray(designPointsToList())
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels
     *  as a 2D array. The rows of the array are the design points.
     *  The row array's 0th element represents the first factor in the list of factor names.
     */
    fun codedDesignPointsTo2DArray(): Array<DoubleArray> {
        return KSLArrays.to2DDoubleArray(codedDesignPointsToList())
    }
}

/**
 *  A factorial design represents a list of design points where every design point
 *  represents a possible row in the cartesian product of the levels for the
 *  factors. That is, all possible combinations of the levels for the factors
 *  are possible design points.  A design point is individually generated
 *  when needed via the design point request functions.
 *
 *  @param factors a set representing the factors used in the design. There must
 *  be 2 factors in the supplied set.
 *  @param name an optional name for the design
 *
 */
class FactorialDesign(
    factors: Set<Factor>,
    name: String? = null
) : Identity(name), FactorialDesignIfc {

    private val myFactors = mutableMapOf<String, Factor>()

    override val factors: Map<String, Factor>
        get() = myFactors

    override val numDesignPoints: Int

    override val factorNames: List<String>

    private val myLevels: List<DoubleArray>
    private val myCodedLevels: List<DoubleArray>

    init {
        require(factors.size >= 2) { "There must be at least 2 factors in the supplied set" }
        var n = 1
        val fList = mutableListOf<DoubleArray>()
        val nList = mutableListOf<String>()
        val cList = mutableListOf<DoubleArray>()
        for (factor in factors) {
            nList.add(factor.name)
            myFactors[factor.name] = factor
            n = n * factor.levels.size
            fList.add(factor.levels())
            cList.add(factor.codedLevels())
        }
        numDesignPoints = n
        myLevels = fList
        factorNames = nList
        myCodedLevels = cList
    }

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *  @param k must be in 1 to numDesignPoints
     */
    fun designPointToArray(k: Int): DoubleArray {
        require(k in 1..numDesignPoints) { "The requested design point $k was on in ${1..numDesignPoints}. " }
        return KSLArrays.cartesianProductRow(myLevels, k - 1)
    }

    /**
     *  Returns the coded design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *  @param k must be in 1 to numDesignPoints
     */
    fun codedDesignPointToArray(k: Int): DoubleArray {
        require(k in 1..numDesignPoints) { "The requested design point $k was on in ${1..numDesignPoints}. " }
        return KSLArrays.cartesianProductRow(myCodedLevels, k - 1)
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0th element represents the first factor in the list of factor names.
     */
    override fun designPointsToList(): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        for (i in 1..numDesignPoints) {
            list.add(designPointToArray(i))
        }
        return list
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0th element represents the first factor in the list of factor names.
     */
    override fun codedDesignPointsToList(): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        for (i in 1..numDesignPoints) {
            list.add(codedDesignPointToArray(i))
        }
        return list
    }

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels. The returned
     *  map holds pairs (factor name, level) for each of the factor settings
     *  at the designated design point.
     *
     *  @param k must be in 1 to numDesignPoints
     */
    override fun designPointToMap(k: Int): Map<String, Double> {
        val rowMap = mutableMapOf<String, Double>()
        val points = designPointToArray(k)
        for ((i, point) in points.withIndex()) {
            rowMap[factorNames[i]] = point
        }
        return rowMap
    }

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *
     *  @param k must be in 1 to numDesignPoints
     *  @return the returned DesignPoint
     */
    override fun designPoint(k: Int): DesignPoint {
        val rowMap = mutableMapOf<Factor, Double>()
        val points = designPointToArray(k)
        for ((i, point) in points.withIndex()) {
            val factor = myFactors[factorNames[i]]!!
            rowMap[factor] = point
        }
        return DesignPoint(k, rowMap)
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     */
    fun designPoints(): List<DesignPoint> {
        return List(numDesignPoints) { designPoint(it + 1) }
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The rows of the list are the design points as maps between factor names and assigned level
     */
    fun designPointsList(): List<Map<String, Double>> {
        val list = mutableListOf<Map<String, Double>>()
        for (i in 1..numDesignPoints) {
            list.add(designPointToMap(i))
        }
        return list
    }

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels. The returned
     *  map holds pairs (factor name, level) for each of the factor settings
     *  at the designated design point.
     *
     *  @param k must be in 1 to numDesignPoints
     */
    fun codedDesignPointToMap(k: Int): Map<String, Double> {
        val rowMap = mutableMapOf<String, Double>()
        val points = codedDesignPointToArray(k)
        for ((i, point) in points.withIndex()) {
            rowMap[factorNames[i]] = point
        }
        return rowMap
    }

    /**
     *  Returns all the coded design points based on the cartesian product of the factors and their coded levels.
     *  The rows of the list are the coded design points as maps between factor names and assigned coded level
     */
    fun codedDesignPointsList(): List<Map<String, Double>> {
        val list = mutableListOf<Map<String, Double>>()
        for (i in 1..numDesignPoints) {
            list.add(codedDesignPointToMap(i))
        }
        return list
    }

    /**
     *  Returns the design points as a data frame. The columns
     *  of the data frame are the factor names and the rows are the
     *  design points.
     */
    fun designPointsAsDataframe(): AnyFrame {
        val points = designPointsTo2DArray()
        val cols = points.toMapOfLists(factorNames)
        return cols.toDataFrame()
    }

    /**
     *  Returns the design points as a data frame. The columns
     *  of the data frame are the factor names and the rows are the
     *  design points.
     */
    fun codedDesignPointsAsDataframe(): AnyFrame {
        val points = codedDesignPointsTo2DArray()
        val cols = points.toMapOfLists(factorNames)
        return cols.toDataFrame()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("FactorialDesign")
        sb.appendLine("name: $name")
        sb.appendLine("number of design points: $numDesignPoints")
        sb.appendLine("Factors")
        for ((name, factor) in factors) {
            sb.appendLine(factor)
        }
        sb.appendLine("First few Design Points")
        val n = min(4, numDesignPoints)
        for (i in 1..n) {
            sb.appendLine("\t$i : ${designPointToArray(i).joinToString()}")
        }
        return sb.toString()
    }

    companion object {

        /**
         *  Creates a two the k factorial design with levels -1 and 1
         *  based on the supplied [names] for each factor. There must
         *  be at least 2 names supplied.
         */
        fun twoToKDesign(names: Set<String>): FactorialDesign {
            require(names.size > 2) { "There must be at least 2 factors in the design" }
            val set = mutableSetOf<Factor>()
            for (name in names) {
                set.add(Factor(name))
            }
            return FactorialDesign(set)
        }

        /**
         *  Create a coded 2-Level full-factorial design matrix
         *
         *  @param numFactors the number of factors in the design
         *  @return the 2d design matrix
         */
        fun fullFactorial2Levels(numFactors: Int): Array<DoubleArray> {
            require(numFactors >= 2) { "There must be at least 2 factors" }
            // make the names
            val names = List(numFactors) { "A$it" }.toSet()
            val fd = twoToKDesign(names)
            return fd.designPointsTo2DArray()
        }
    }

}


