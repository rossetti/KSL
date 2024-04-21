package ksl.controls.experiments

import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import kotlin.math.min


/**
 *  A factorial design represents a list of design points where every design point
 *  represents a possible row in the cartesian product of the levels for the
 *  factors. That is, all possible combinations of the levels for the factors
 *  are possible design points.  A design point is individually generated
 *  when needed via the associated iterator for the design.
 *
 *  @param factors a set representing the factors used in the design. There must
 *  be 2 factors in the supplied set.
 *  @param name an optional name for the design
 *
 */
class FactorialDesignV2(
    factors: Set<Factor>,
    name: String? = null
) : Identity(name), ExperimentalDesignIfc {
    private val myFactors = mutableMapOf<String, Factor>()

    override val factors: Map<String, Factor>
        get() = myFactors

    val numDesignPoints: Int

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
     *  Returns the design point as an array of level values for the kth row of the
     *  factorial design based on the cartesian product of the factors and their levels.
     *
     *  @param k must be in 1 to numDesignPoints
     *  @param coded indicates if the points should be coded, the default is false
     */
    private fun designPointToArray(k: Int, coded: Boolean = false): DoubleArray {
        require(k in 1..numDesignPoints) { "The requested design point $k was on in ${1..numDesignPoints}." }
        val levels = if (coded) myCodedLevels else myLevels
        return KSLArrays.cartesianProductRow(levels, k - 1)
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0th element represents the first factor in the list of factor names.
     *  @param coded indicates if the points should be coded, the default is false
     */
    private fun designPointsToList(coded: Boolean = false): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        for (i in 1..numDesignPoints) {
            list.add(designPointToArray(i, coded))
        }
        return list
    }

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *
     *  @param k must be in 1 to numDesignPoints
     *  @return the returned DesignPoint
     */
    private fun designPoint(k: Int): DesignPoint {
        val rowMap = mutableMapOf<Factor, Double>()
        val points = designPointToArray(k)
        for ((i, point) in points.withIndex()) {
            val factor = myFactors[factorNames[i]]!!
            rowMap[factor] = point
        }
        return DesignPoint(this, k, rowMap)
    }

    /**
     *  This iterator should present each design point
     *  until all points in the design have been presented.
     */
    private inner class DesignPointIterator : DesignPointIteratorIfc {
        override var count: Int = 0
            private set

        override var last: DesignPoint? = null
            private set

        override fun hasNext(): Boolean {
            return count < numDesignPoints
        }

        override fun next(): DesignPoint {
            count++
            last = designPoint(count)
            return last!!
        }

    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     */
    fun designPoints(): List<DesignPoint> {
        return List(numDesignPoints) { designPoint(it + 1) }
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels
     *  as a 2D array.  The rows of the array are the design points.
     *  The row array's 0th element represents the first factor in the list of factor names.
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun designPointsTo2DArray(coded: Boolean = false): Array<DoubleArray> {
        return KSLArrays.to2DDoubleArray(designPointsToList(coded))
    }

    /**
     *  Returns an iterator that produces the design points
     *  in order from 1 to the number of design points.
     */
    override fun iterator(): DesignPointIteratorIfc {
        return DesignPointIterator()
    }

    /**
     *  Returns the design points as a data frame. The columns
     *  of the data frame are the factor names and the rows are the
     *  design points.
     *  @param coded indicates if the points are coded. The default is false.
     */
    fun designPointsAsDataframe(coded: Boolean = false): AnyFrame {
        val points = designPointsTo2DArray(coded)
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
        fun twoToKDesign(names: Set<String>): FactorialDesignV2 {
            require(names.size > 2) { "There must be at least 2 factors in the design" }
            val set = mutableSetOf<Factor>()
            for (name in names) {
                set.add(Factor(name))
            }
            return FactorialDesignV2(set)
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