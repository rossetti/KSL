package ksl.controls.experiments

import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.io.print
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 *  A factorial design represents a list of design points where every design point
 *  represents a possible row in the cartesian product of the levels for the
 *  factors. That is, all possible combinations of the levels for the factors
 *  are possible design points.
 *
 */
class FactorialDesign(
    factors: Set<Factor>,
    name: String? = null
) : Identity(name) {

    private val myFactors = mutableMapOf<String, Factor>()

    val factors: Map<String, Factor>
        get() = myFactors

    val numDesignPoints: Int

    val factorNames: List<String>

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
     *  Returns the name of the factor. The first factor is at k = 1
     *  @param k must be in 1 to number of factors
     */
    fun factorName(k: Int): String = factorNames[k - 1]

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *  @param k must be in 1 to numDesignPoints
     */
    fun designPoint(k: Int): DoubleArray {
        require(k in 1..numDesignPoints) { "The requested design point $k was on in ${1..numDesignPoints}. " }
        return KSLArrays.cartesianProductRow(myLevels, k - 1)
    }

    /**
     *  Returns the coded design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels.
     *  @param k must be in 1 to numDesignPoints
     */
    fun codedDesignPoint(k: Int): DoubleArray {
        require(k in 1..numDesignPoints) { "The requested design point $k was on in ${1..numDesignPoints}. " }
        return KSLArrays.cartesianProductRow(myCodedLevels, k - 1)
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0 element represents the first factor in the list of factor names.
     */
    fun designPoints(): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        for (i in 1..numDesignPoints) {
            list.add(designPoint(i))
        }
        return list
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The rows of the array are the design points.
     *  The row arrays of the returned list are the design points. The row
     *  array's 0 element represents the first factor in the list of factor names.
     */
    fun designPointsArray(): Array<DoubleArray> {
        return KSLArrays.to2DDoubleArray(designPoints())
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0 element represents the first factor in the list of factor names.
     */
    fun codedDesignPoints(): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        for (i in 1..numDesignPoints) {
            list.add(codedDesignPoint(i))
        }
        return list
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The rows of the array are the design points.
     *  The row arrays of the returned list are the design points. The row
     *  array's 0 element represents the first factor in the list of factor names.
     */
    fun codedDesignPointsArray(): Array<DoubleArray> {
        return KSLArrays.to2DDoubleArray(codedDesignPoints())
    }

    /**
     *  Returns the design point at the kth row of the factorial design based
     *  on the cartesian product of the factors and their levels. The returned
     *  map holds pairs (factor name, level) for each of the factor settings
     *  at the designated design point.
     *
     *  @param k must be in 1 to numDesignPoints
     */
    fun designPointAsMap(k: Int): Map<String, Double> {
        val rowMap = mutableMapOf<String, Double>()
        val points = designPoint(k)
        for ((i, point) in points.withIndex()) {
            rowMap[factorNames[i]] = point
        }
        return rowMap
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The rows of the list are the design points as maps between factor names and assigned level
     */
    fun designPointsList(): List<Map<String, Double>> {
        val list = mutableListOf<Map<String, Double>>()
        for (i in 1..numDesignPoints) {
            list.add(designPointAsMap(i))
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
    fun codedDesignPointAsMap(k: Int): Map<String, Double> {
        val rowMap = mutableMapOf<String, Double>()
        val points = codedDesignPoint(k)
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
            list.add(codedDesignPointAsMap(i))
        }
        return list
    }

    /**
     *  Returns the design points as a data frame. The columns
     *  of the data frame are the factor names and the rows are the
     *  design points.
     */
    fun designPointsAsDataframe(): AnyFrame {
        val points = designPointsArray()
        val cols = points.toMapOfLists(factorNames)
        return cols.toDataFrame()
    }

    /**
     *  Returns the design points as a data frame. The columns
     *  of the data frame are the factor names and the rows are the
     *  design points.
     */
    fun codedDesignPointsAsDataframe(): AnyFrame {
        val points = codedDesignPointsArray()
        val cols = points.toMapOfLists(factorNames)
        return cols.toDataFrame()
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
            for(name in names){
                set.add(Factor(name))
            }
            return FactorialDesign(set)
        }
    }


}

fun main(){
    testFD()
}

fun testFD(){
    val f1 = Factor("A", doubleArrayOf(1.0, 2.0, 3.0, 4.0))
    val f2 = Factor("B", doubleArrayOf(5.0, 9.0))
    val factors = setOf(f1, f2)
    val fd = FactorialDesign(factors)
    println("Factorial Design as Data Frame")
    println(fd.designPointsAsDataframe())
    println()
    println("Coded Factorial Design as Data Frame")
    println(fd.codedDesignPointsAsDataframe())
    println()
    val array = fd.designPointsArray()
    array.print()
    println()
    val kd = FactorialDesign.twoToKDesign(setOf("A", "B", "C", "D"))
    println("Factorial Design as Data Frame")
    println(kd.designPointsAsDataframe())
    println()
}