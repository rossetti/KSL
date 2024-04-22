package ksl.controls.experiments

import ksl.utilities.KSLArrays
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

interface ExperimentalDesignIfc : Iterable<DesignPoint> {

    /**
     *  The factors associated with the design held by
     *  name.
     */
    val factors: Map<String, Factor>

    /**
     *  The names of the factors within a list.
     */
    val factorNames: List<String>

    /**
     *  Returns the name of the factor. The first factor is at k = 1
     *  @param k must be in 1 to number of factors
     */
    fun factorName(k: Int): String = factorNames[k - 1]

    /**
     *  Returns an iterator that produces the design points
     *  in order from 1 to the number of design points.
     *  @param defaultNumReplications the number of replications for the design points returned from the iterator
     *  Must be greater or equal to 1.
     */
    fun designIterator(defaultNumReplications: Int = 1): DesignPointIteratorIfc

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     */
    fun designPoints(): List<DesignPoint> {
        return iterator().asSequence().toList()
    }

    /**
     *  Returns all the design points based on the cartesian product of the factors and their levels.
     *  The element arrays of the returned list are the design points. The element
     *  array's 0th element represents the first factor in the list of factor names.
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun designPointsToList(coded: Boolean = false): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        val itr = iterator()
        while (itr.hasNext()) {
            val point = if (coded) itr.next().codedValues() else itr.next().values()
            list.add(point)
        }
        return list
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
}