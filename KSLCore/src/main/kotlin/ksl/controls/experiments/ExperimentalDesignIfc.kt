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
     *  To facilitate the specification of a linear model for the design
     */
    val linearModel: LinearModel
        get() = LinearModel(factors.keys)

    /**
     *  The number of factor in the design
     */
    val numFactors
        get() = factorNames.size

    /**
     *  Checks if the settings for the factors are valid for this design
     *  @param settings the map contains each factor and the setting of the factor
     *  @param enforceRange true indicates if the range limits of the factor are
     *  used in the validation check. Not enforcing the range check allows settings
     *  that may be out of range limits for the factors
     */
    fun isValid(settings: Map<Factor, Double>, enforceRange:Boolean = true): Boolean {
        if (settings.isEmpty()) {
            return false
        }
        for ((f, v) in settings.entries) {
            if (!factors.containsValue(f)) { return false}
            if (enforceRange){
                if (!f.isInRange(v)) return false
            }
        }
        return true
    }

    /**
     *  Returns the name of the factor. The first factor is at k = 1
     *  @param k must be in 1 to number of factors
     */
    fun factorName(k: Int): String = factorNames[k - 1]

    /**
     *  Returns an iterator that produces the design points
     *  in order from 1 to the number of design points.
     *  @param replications the number of replications for the design points returned from the iterator
     *  If null (the default) the current setting for the number of replications of the design point
     *  is used; otherwise, the design point's number of replications will be updated by the supplied
     *  value. Must be greater than 0
     */
    fun designIterator(replications: Int? = null): DesignPointIteratorIfc

    /**
     *  Returns all the design points in the experiment
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

    /**
     *  Makes a center point for the factors of the design in the
     *  original measurement units
     */
    fun centerPoint(): DoubleArray {
        val list = mutableListOf<Double>()
        for (factor in factors.values) {
            list.add(factor.midPoint)
        }
        return list.toDoubleArray()
    }

    /**
     *  Converts the original values to code values
     *  @param rawValues the values for each factor. The size must be the number of factors
     *  with element 0 representing the first factor
     */
    fun toCodedValues(rawValues: DoubleArray): DoubleArray {
        require(rawValues.size == factors.size) { "The number of values (${rawValues.size}) must be equal to the number of factors (${factors.size})" }
        val array = DoubleArray(rawValues.size)
        for (i in rawValues.indices) {
            array[i] = factors[factorNames[i]]!!.toCodedValue(rawValues[i])
        }
        return array
    }

    /**
     *  Converts the coded values to values on the original measurement scale.
     *  @param codedValues the values for each factor. The size must be the number of factors
     *  with element 0 representing the first factor
     */
    fun toOriginalValues(codedValues: DoubleArray): DoubleArray {
        require(codedValues.size == factors.size) { "The number of values (${codedValues.size}) must be equal to the number of factors (${factors.size})" }
        val array = DoubleArray(codedValues.size)
        for (i in codedValues.indices) {
            array[i] = factors[factorNames[i]]!!.toOriginalValue((codedValues[i]))
        }
        return array
    }
}