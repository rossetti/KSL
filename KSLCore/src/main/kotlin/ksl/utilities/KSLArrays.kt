/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities

import ksl.utilities.math.FunctionIfc
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.statistic.*
import java.text.DecimalFormat
import java.util.*
import kotlin.collections.mapIndexed
import kotlin.math.*

/**
 * This class has some array manipulation methods that I have found useful over the years.
 */
object KSLArrays {
    /**
     * Returns the index associated with the minimum element in the array. For
     * ties, this returns the first found.
     *
     * @param x the array to search must not be empty
     * @return the index associated with the minimum element
     */
    @JvmStatic
    @Suppress("unused")
    fun indexOfMin(x: DoubleArray): Int {
        require(x.isNotEmpty()) { "The array was empty" }
        var index = 0
        var min = Double.MAX_VALUE
        for (i in x.indices) {
            if (x[i] < min) {
                min = x[i]
                index = i
            }
        }
        return index
    }

    /**
     * @param x the array to search. must not be empty
     * @return the minimum value in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun min(x: DoubleArray): Double {
        return x[indexOfMin(x)]
    }

    /**
     * Returns the index associated with the maximum element in the array. For
     * ties, this returns the first found.
     *
     * @param x the array to search. must not be empty
     * @return the index associated with the maximum element
     */
    fun indexOfMax(x: DoubleArray): Int {
        require(x.isNotEmpty()) { "The array was empty" }
        var index = 0
        var max = Double.MIN_VALUE
        for (i in x.indices) {
            if (x[i] > max) {
                max = x[i]
                index = i
            }
        }
        return index
    }

    /**
     * @param x the array to search. must not be empty
     * @return the maximum value in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun max(x: DoubleArray): Double {
        return x[indexOfMax(x)]
    }

    /**
     * Returns the index associated with the minimum element in the array. For
     * ties, this returns the first found.
     *
     * @param x the array to search. must not be empty
     * @return the index associated with the minimum element
     */
    @JvmStatic
    @Suppress("unused")
    fun indexOfMin(x: IntArray): Int {
        require(x.isNotEmpty()) { "The array was empty" }
        var index = 0
        var min = Double.MAX_VALUE
        for (i in x.indices) {
            if (x[i] < min) {
                min = x[i].toDouble()
                index = i
            }
        }
        return index
    }

    /**
     * @param x the array to search. must not be empty
     * @return the minimum value in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun min(x: IntArray): Int {
        return x[indexOfMin(x)]
    }

    /**
     * Returns the index associated with the maximum element in the array. For
     * ties, this returns the first found.
     *
     * @param x the array to search. must not be empty
     * @return the index associated with the maximum element
     */
    @JvmStatic
    @Suppress("unused")
    fun indexOfMax(x: IntArray): Int {
        require(x.isNotEmpty()) { "The array was empty" }
        var index = 0
        var max = Double.MIN_VALUE
        for (i in x.indices) {
            if (x[i] > max) {
                max = x[i].toDouble()
                index = i
            }
        }
        return index
    }

    /**
     * @param x the array to search. must not be empty
     * @return the maximum value in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun max(x: IntArray): Int {
        return x[indexOfMax(x)]
    }

    /**
     * Returns the index associated with the minimum element in the array. For
     * ties, this returns the first found
     *
     * @param x the array to search must not be empty
     * @return the index associated with the minimum element
     */
    @JvmStatic
    @Suppress("unused")
    fun indexOfMin(x: LongArray): Int {
        require(x.isNotEmpty()) { "The array was empty" }
        var index = 0
        var min = Double.MAX_VALUE
        for (i in x.indices) {
            if (x[i] < min) {
                min = x[i].toDouble()
                index = i
            }
        }
        return index
    }

    /**
     * @param x the array to search must not be empty
     * @return the minimum value in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun min(x: LongArray): Long {
        return x[indexOfMin(x)]
    }

    /**
     * Returns the index associated with the maximum element in the array. For
     * ties, this returns the first found
     *
     * @param x the array to search must not be empty
     * @return the index associated with the maximum element
     */
    @JvmStatic
    @Suppress("unused")
    fun indexOfMax(x: LongArray): Int {
        require(x.isNotEmpty()) { "The array was empty" }
        var index = 0
        var max = Double.MIN_VALUE
        for (i in x.indices) {
            if (x[i] > max) {
                max = x[i].toDouble()
                index = i
            }
        }
        return index
    }

    /**
     * @param x the array to search must not be empty
     * @return the maximum value in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun max(x: LongArray): Long {
        return x[indexOfMax(x)]
    }

    /**
     * @param array the array to operate on
     * @return max() - min()
     */
    @JvmStatic
    @Suppress("unused")
    fun range(array: DoubleArray): Double = max(array) - min(array)

    /**
     * @param array the array to operate on
     * @return max() - min()
     */
    @JvmStatic
    @Suppress("unused")
    fun range(array: IntArray): Int = max(array) - min(array)

    /**
     * @param array the array to operate on
     * @return max() - min()
     */
    @JvmStatic
    @Suppress("unused")
    fun range(array: LongArray): Long = max(array) - min(array)

    /**
     * If the array is empty, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence for the element
     */
    @JvmStatic
    @Suppress("unused")
    fun findIndex(element: Int, array: IntArray): Int {
        // find length of array
        var i = 0
        // traverse in the array
        while (i < array.size) {
            // if the i-th element is t
            // then return the index
            i = if (array[i] == element) {
                return i
            } else {
                i + 1
            }
        }
        return -1
    }

    /**
     *
     * @param array the array to check
     * @return true if the array has at least one zero element
     */
    @JvmStatic
    @Suppress("unused")
    fun hasZero(array: IntArray): Boolean {
        return findIndex(0, array) >= 0
    }

    /**
     *
     * @param array the array to check
     * @return true if the array has at least one zero element
     */
    @JvmStatic
    @Suppress("unused")
    fun hasZero(array: DoubleArray): Boolean {
        return findIndex(0.0, array) >= 0
    }

    /**
     *
     * @param array the array to check
     * @return true if the array has at least one zero element
     */
    @JvmStatic
    @Suppress("unused")
    fun hasZero(array: LongArray): Boolean {
        return findIndex(0, array) >= 0
    }

    /**
     * If the array is empty or the element is not found, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence for the element
     */
    @JvmStatic
    @Suppress("unused")
    fun findIndex(element: Double, array: DoubleArray): Int {
        // find length of array
        var i = 0
        // traverse in the array
        while (i < array.size) {
            // if the i-th element is t
            // then return the index
            i = if (array[i] == element) {
                return i
            } else {
                i + 1
            }
        }
        return -1
    }

    /**
     * @param element the element to check
     * @param array the array to check
     * @return true if the array as at least one occurrence of the element
     */
    @JvmStatic
    @Suppress("unused")
    fun hasElement(element: Double, array: DoubleArray): Boolean {
        return findIndex(element, array) >= 0
    }

    /**
     * If the array is empty or the element is not found, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence for the element
     */
    @JvmStatic
    @Suppress("unused")
    fun findIndex(element: Long, array: LongArray): Int {
        // find length of array
        var i = 0
        // traverse in the array
        while (i < array.size) {
            // if the i-th element is t
            // then return the index
            i = if (array[i] == element) {
                return i
            } else {
                i + 1
            }
        }
        return -1
    }

    /**
     * @param element the element to check
     * @param array the array to check
     * @return true if the array as at least one occurrence of the element
     */
    @JvmStatic
    @Suppress("unused")
    fun hasElement(element: Long, array: LongArray): Boolean {
        return findIndex(element, array) >= 0
    }

    /**
     * @param element the element to check
     * @param array the array to check
     * @return true if the array as at least one occurrence of the element
     */
    @JvmStatic
    @Suppress("unused")
    fun hasElement(element: Int, array: IntArray): Boolean {
        return findIndex(element, array) >= 0
    }

    /**
     * If the array is empty or the element is not found, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence for the element
     */
    @JvmStatic
    @Suppress("unused")
    fun findIndex(element: String, array: Array<String>): Int {
        // find length of array
        var i = 0
        // traverse in the array
        while (i < array.size) {
            // if the i-th element is t
            // then return the index
            i = if (array[i] == element) {
                return i
            } else {
                i + 1
            }
        }
        return -1
    }

    /**
     * Returns a new array that has been scaled so that the values are between
     * the minimum and maximum values of the supplied array
     *
     * @param array the array to scale
     * @return the scaled array
     */
    @JvmStatic
    @Suppress("unused")
    fun minMaxScaledArray(array: DoubleArray): DoubleArray {
        val max = max(array)
        val min = min(array)
        val range = max - min
        require(range > 0.0) { "Array cannot be scaled because min() = max()" }
        val x = DoubleArray(array.size)
        for (i in array.indices) {
            x[i] = (array[i] - min) / range
        }
        return x
    }

    /**
     * Returns a new array that has been scaled so that the values are
     * the (x - avg)/sd values of the supplied array
     *
     * @param array the array to scale
     * @return the scaled array
     */
    @JvmStatic
    @Suppress("unused")
    fun normScaledArray(array: DoubleArray): DoubleArray {
        val s = Statistic(array)
        val avg = s.average
        val sd = s.standardDeviation
        require(sd > 0.0) { "The array cannot be scaled because std dev == 0.0" }
        val x = DoubleArray(array.size)
        for (i in array.indices) {
            x[i] = (array[i] - avg) / sd
        }
        return x
    }

    /**
     * Copies all but element index of the array fromA into the array toB
     * If fromA has 1 element, toB will be empty
     * @param index index of the element to leave out, must be 0 to fromA.length-1
     * @param fromA array to copy from
     * @param toB   array to copy to, must be length fromA.length - 1
     * @return a reference to the array toB
     */
    @JvmStatic
    @Suppress("unused")
    fun copyWithout(index: Int, fromA: DoubleArray, toB: DoubleArray = DoubleArray(fromA.size - 1)): DoubleArray {
        require(index >= 0) { "The index must be >= 0" }
        require(index <= fromA.size - 1) { "The index must be <= fromA.length-1" }
        require(toB.size == fromA.size - 1) { "The length of toB was not fromA.length - 1" }
        if (fromA.size == 1) {
            return toB
        }
        var k = 0
        for (j in fromA.indices) {
            if (j != index) {
                toB[k] = fromA[j]
                k++
            }
        }
        return toB
    }

    /**
     * @param a the double[nRow][nCol] array, must not be null, must be rectangular with nRow rows
     * @param b the double[nRows] array, must not be null, must have nRow elements
     * @return post multiplies a by b, a result with nRow elements representing the dot product of
     * b with each row of a.
     */
    @JvmStatic
    @Suppress("unused")
    fun postProduct(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        require(isRectangular(a)) { "The double[][] array was not rectangular" }
        require(a.size == b.size) { "The double[][] array is not multiplication compatible with the double[]" }
        require(b.isNotEmpty()) { "The arrays were empty!" }
        val result = DoubleArray(b.size)
        for (i in a.indices) {
            result[i] = dotProduct(a[i], b)
        }
        return result
    }

    /**
     * @param a the first array
     * @param b the second array
     * @return the summed product of the two arrays
     */
    @JvmStatic
    @Suppress("unused")
    fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "The length of the arrays was not equal" }
        require(a.isNotEmpty()) { "The arrays were empty!" }
        var sum = 0.0
        for (i in a.indices) {
            sum = sum + a[i] * b[i]
        }
        return sum
    }

    /**
     * The arrays must be rectangular, and n columns of first must
     * be same and n rows for second.
     *
     * @param first  the first array
     * @param second the second array
     * @return true if arrays can be multiplied
     */
    @JvmStatic
    @Suppress("unused")
    fun isMultiplyCompatible(first: Array<DoubleArray>, second: Array<DoubleArray>): Boolean {
        if (!isRectangular(first)) {
            return false
        }
        if (!isRectangular(second)) {
            return false
        }
        val nColsFirst: Int = numColumns(first)
        val nRowsSecond: Int = numRows(second)
        return nColsFirst == nRowsSecond
    }

    /**
     * @param first  the first array must be rectangular
     * @param second the second array must be rectangular
     * @return true if arrays have the same elements
     */
    @JvmStatic
    @Suppress("unused")
    fun isEqual(first: Array<DoubleArray>, second: Array<DoubleArray>): Boolean {
        require(isRectangular(first)) { "The first array was not rectangular" }
        require(isRectangular(second)) { "The second array was not rectangular" }
        val nColsFirst: Int = numColumns(first)
        val nRowsFirst: Int = numRows(first)
        val nColsSecond: Int = numColumns(second)
        val nRowsSecond: Int = numRows(second)
        if (nRowsFirst != nRowsSecond) {
            return false
        }
        if (nColsFirst != nColsSecond) {
            return false
        }
        for (i in 0 until nRowsFirst) {
            for (j in 0 until nColsFirst) {
                if (first[i][j] != second[i][j]) return false
            }
        }
        return true
    }

    /**
     * The arrays must be rectangular with the number of rows of the first
     * array equal to the number of columns for the second array.
     *
     * @param first  the first array
     * @param second the second array
     * @return the product of the arrays
     */
    @JvmStatic
    @Suppress("unused")
    fun multiply(first: Array<DoubleArray>, second: Array<DoubleArray>): Array<DoubleArray> {
        require(isRectangular(first)) { "The first array was not rectangular" }
        require(isRectangular(second)) { "The second array was not rectangular" }
        val nColsFirst: Int = numColumns(first)
        val nRowsSecond: Int = numRows(second)
        require(nColsFirst == nRowsSecond) { "The arrays are not multiplication compatible" }
        val nr: Int = numRows(first)
        val nc: Int = numColumns(second)
        val result = Array(nr) { DoubleArray(nc) }
        for (i in 0 until nr) {
            for (j in 0 until nc) {
                for (k in 0 until nRowsSecond) {
                    result[i][j] = result[i][j] + first[i][k] * second[k][j]
                }
            }
        }
        return result
    }

    /**
     * @param array a 2-D rectangular array
     * @return the number of rows in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun numRows(array: Array<DoubleArray>): Int {
        require(isRectangular(array)) { "The array was not rectangular" }
        return array.size
    }

    /**
     * @param array a 2-D rectangular array
     * @return the number of columns in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun numColumns(array: Array<DoubleArray>): Int {
        require(isRectangular(array)) { "The array was not rectangular" }
        return array[0].size
    }

    /** This operation is in-place.
     * @param a the array to add the constant to. The array is changed.
     * @param c the constant to add to each element
     * @return the transformed array
     */
    @JvmStatic
    @Suppress("unused")
    fun addConstant(a: DoubleArray, c: Double): DoubleArray {
        for (i in a.indices) {
            a[i] = a[i] + c
        }
        return a
    }

    /** This operation is in-place.
     * @param a the array to add the constant to. The array is changed.
     * @param c the constant to subtract from each element
     * @return the transformed array
     */
    @JvmStatic
    @Suppress("unused")
    fun subtractConstant(a: DoubleArray, c: Double): DoubleArray {
        return addConstant(a, -c)
    }

    /** This operation is in-place.
     * @param a the array to multiply the constant by. The array is changed.
     * @param c the constant to multiply against each element
     * @return the transformed array
     */
    @JvmStatic
    @Suppress("unused")
    fun multiplyConstant(a: DoubleArray, c: Double): DoubleArray {
        for (i in a.indices) {
            a[i] = a[i] * c
        }
        return a
    }

    /** This operation is in-place.
     * @param a the array to divide the constant by. The array is changed.
     * @param c the constant to divide each element and cannot be zero
     * @return the transformed array
     */
    @JvmStatic
    @Suppress("unused")
    fun divideConstant(a: DoubleArray, c: Double): DoubleArray {
        require(c != 0.0) { "Cannot divide by zero" }
        return multiplyConstant(a, 1.0 / c)
    }

    /**
     * Multiplies the two arrays element by element. Arrays must have the same length.
     *
     * @param a the first array
     * @param b the second array
     * @return a new array containing a[i]*b[i]
     */
    @JvmStatic
    @Suppress("unused")
    fun multiplyElements(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "The array lengths must match" }
        val c = DoubleArray(a.size)
        for (i in a.indices) {
            c[i] = a[i] * b[i]
        }
        return c
    }

    /**
     * Divides the arrays' element by element. Arrays must have the same length and must not be null.
     *
     * @param a the first array
     * @param b the second array and must not have any zero elements
     * @return the array containing a[i]/b[i]
     */
    @JvmStatic
    @Suppress("unused")
    fun divideElements(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(!hasZero(b)) { "The divisor array has at least one element that is 0.0" }
        require(a.size == b.size) { "The array lengths must match" }
        val c = DoubleArray(a.size)
        for (i in a.indices) {
            c[i] = a[i] / b[i]
        }
        return c
    }

    /**
     * Assumes that the 2-D array can be ragged. Returns the number of columns
     * necessary that would cause the array to not be ragged. In other words,
     * the minimum number of columns to make the array an un-ragged array (matrix) where
     * all row arrays have the same number of elements.
     *
     * @param array2D the array to check
     * @return the minimum number of columns in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun minNumColumns(array2D: Array<DoubleArray>): Int {
        var min = Int.MAX_VALUE
        for (row in array2D.indices) {
            if (array2D[row].size < min) {
                min = array2D[row].size
            }
        }
        return min
    }

    /**
     * Copies the supplied array by trimming to the minimum number of columns for the
     * supplied (potentially ragged) array so that the returned array is rectangular,
     * where all row arrays have the same number of elements (columns)
     *
     * @param array2D the array to copy
     * @return the copy
     */
    @JvmStatic
    @Suppress("unused")
    fun trimToRectangular(array2D: Array<DoubleArray>): Array<DoubleArray> {
        val rows = array2D.size
        val cols = minNumColumns(array2D)
        val matrix = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                matrix[i][j] = array2D[i][j]
            }
        }
        return matrix
    }

    /**
     * Copies the supplied 2-D array by expanding to the maximum number of columns for the
     * supplied (ragged) array so that the returned array is rectangular,
     * where all row arrays have the same number of elements (columns).
     *
     *
     * The expanded elements will be filled with the supplied fill value
     *
     * @param array2D the array to copy
     * @param fillValue the value to fill if needed, default is 0.0
     * @return the copy
     */
    @JvmStatic
    @Suppress("unused")
    fun expandToRectangular(array2D: Array<DoubleArray>, fillValue: Double = 0.0): Array<DoubleArray> {
        val rows = array2D.size
        val cols = maxNumColumns(array2D)
        val matrix = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                if (j > array2D[i].size) {
                    matrix[i][j] = fillValue
                } else {
                    matrix[i][j] = array2D[i][j]
                }
            }
        }
        return matrix
    }

    /**
     * A 2-D array is rectangular if all rows have the same number of elements (columns).
     *
     * @param array2D the array to check
     * @return true if the array is rectangular
     */
    @JvmStatic
    @Suppress("unused")
    fun <T> isRectangular(array2D: Array<Array<T>>): Boolean {
        if (array2D.isEmpty()) {
            return false // no rows can't be rectangular
        }
        val nc: Int = array2D[0].size // number of columns in first row, all rows must have this
        for (i in array2D.indices) {
            if (array2D[i].size != nc) {
                return false
            }
        }
        return true
    }

    /**
     * The collection of arrays is considered rectangular if all arrays in the collection
     * have the same number of elements.
     *
     * @param collection the array to check
     * @return true if the array is rectangular
     */
    @JvmStatic
    @Suppress("unused")
    fun isRectangular(collection: Collection<DoubleArray>): Boolean {
        if (collection.isEmpty()) {
            return false // no rows can't be rectangular
        }
        val nc: Int = collection.first().size // number of elements in first array, all rows must have this
        for (array in collection) {
            if (array.size != nc) {
                return false
            }
        }
        return true
    }

    /**
     * An array is rectangular if all rows have the same number of elements (columns).
     *
     * @param array2D the array to check
     * @return true if the array is rectangular
     */
    @JvmStatic
    @Suppress("unused")
    fun isRectangular(array2D: Array<DoubleArray>): Boolean {
        if (array2D.isEmpty()) {
            return false // no rows can't be rectangular
        }
        val nc: Int = array2D[0].size // number of columns in first row, all rows must have this
        for (i in array2D.indices) {
            if (array2D[i].size != nc) {
                return false
            }
        }
        return true
    }

    /**
     * An array is rectangular if all rows have the same number of elements (columns).
     *
     * @param array2D the array to check
     * @return true if the array is rectangular
     */
    @JvmStatic
    @Suppress("unused")
    fun isRectangular(array2D: Array<IntArray>): Boolean {
        if (array2D.isEmpty()) {
            return false // no rows can't be rectangular
        }
        val nc: Int = array2D[0].size // number of columns in first row, all rows must have this
        for (i in array2D.indices) {
            if (array2D[i].size != nc) {
                return false
            }
        }
        return true
    }

    /**
     * An array is rectangular if all rows have the same number of elements (columns).
     *
     * @param array2D the array to check
     * @return true if the array is rectangular
     */
    @JvmStatic
    @Suppress("unused")
    fun isRectangular(array2D: Array<LongArray>): Boolean {
        if (array2D.isEmpty()) {
            return false // no rows can't be rectangular
        }
        val nc: Int = array2D[0].size // number of columns in first row, all rows must have this
        for (i in array2D.indices) {
            if (array2D[i].size != nc) {
                return false
            }
        }
        return true
    }

    /**
     * @param array the square array
     * @return the diagonal elements of the array as an array
     */
    @JvmStatic
    @Suppress("unused")
    fun diagonal(array: Array<DoubleArray>): DoubleArray {
        require(isSquare(array)) { "The diagonal cannot be extracted because the array is not square" }
        val diagonal = DoubleArray(array.size)
        for (i in array.indices) {
            diagonal[i] = array[i][i]
        }
        return diagonal
    }

    /**
     * @param array the array to check
     * @return true if the number of rows equals the number of columns
     */
    @JvmStatic
    @Suppress("unused")
    fun isSquare(array: Array<DoubleArray>): Boolean {
        if (array.isEmpty()) {
            return false // no rows can't be square
        }
        // must be rectangular and nc = nr
        return if (isRectangular(array)) {
            val nc: Int = array[0].size // number of columns in first row, all rows must have this
            val nr = array.size
            nc == nr
        } else {
            false
        }
    }

    /**
     * Assumes that the array can be ragged. Returns the number of elements in
     * the row array that has the most elements.
     *
     * @param array2D the array to check,
     * @return the minimum number of columns in the array
     */
    @JvmStatic
    @Suppress("unused")
    fun maxNumColumns(array2D: Array<DoubleArray>): Int {
        var max = Int.MIN_VALUE
        for (row in array2D.indices) {
            if (array2D[row].size > max) {
                max = array2D[row].size
            }
        }
        return max
    }

    /**
     * @param k      the kth column to be extracted (zero-based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e., all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    @JvmStatic
    @Suppress("unused")
    fun column(k: Int, matrix: Array<DoubleArray>): DoubleArray {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        val column = DoubleArray(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][k]
        }
        return column
    }

    /**
     * @param k      the kth column to be extracted (zero-based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e., all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    @JvmStatic
    @Suppress("unused")
    fun column(k: Int, matrix: Array<IntArray>): IntArray {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        val column = IntArray(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][k]
        }
        return column
    }

    /**
     * @param k      the kth column to be extracted (zero-based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e., all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    @JvmStatic
    @Suppress("unused")
    fun column(k: Int, matrix: Array<LongArray>): LongArray {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        val column = LongArray(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][k]
        }
        return column
    }

//    /**
//     * @param index  the column to be extracted (zero based indexing)
//     * @param matrix must not be null, assumed 2D rectangular array (i.e. all rows have the same number of columns)
//     * @return a copy of the extracted column
//     */
//    fun column(index: Int, matrix: Array<Array<Any>>): Array<Any?> {
//        require(isRectangular(matrix)) { "The matrix was not rectangular" }
//        //TODO can this be made generic
//        val column = arrayOfNulls<Any>(matrix.size) // Here I assume a rectangular 2D array!
//        for (i in column.indices) {
//            column[i] = matrix[i][index]
//        }
//        return column
//    }

    /**
     * @param index  the column to be extracted (zero-based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e., all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    @JvmStatic
    @Suppress("unused")
    inline fun <reified T> column(index: Int, matrix: Array<Array<T>>): Array<T> {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        return Array(matrix.size) { i -> matrix[i][index] }
    }

    /**  Converts the 2-D array to a 1-D array by processing
     *   the source [src] array row-wise and concatenating the rows.
     *   For example, if the data is organized as follows:
     *
     *   1  2   3
     *   4  5   6
     *   7  8   9
     *
     *   Then the resulting array will be (1,2,3,4,5,6,7,8,9).
     *   In general, the source array may be ragged.
     *
     */
    @JvmStatic
    @Suppress("unused")
    fun concatenateTo1DArray(src: Array<DoubleArray>): DoubleArray {
        if (src.isEmpty()) {
            return doubleArrayOf()
        }
        val list = mutableListOf<Double>()
        for (array in src) {
            for (x in array) {
                list.add(x)
            }
        }
        return list.toDoubleArray()
    }

    /**
     * @param src the source array to copy
     * @return a copy of the array
     */
    @JvmStatic
    @Suppress("unused")
    fun copy2DArray(src: Array<DoubleArray>): Array<DoubleArray> {
        if (src.isEmpty()) {
            return Array(0) { DoubleArray(0) }
        }
        return Array(src.size) { src[it].copyOf() }
    }

    /**
     * @param src the source array to copy
     * @return a copy of the array
     */
    @JvmStatic
    @Suppress("unused")
    fun copy2DArray(src: Array<IntArray>): Array<IntArray> {
        if (src.isEmpty()) {
            return Array(0) { IntArray(0) }
        }
        return Array(src.size) { src[it].copyOf() }
    }

    /**
     * @param src the source array to copy
     * @return a copy of the array
     */
    @JvmStatic
    @Suppress("unused")
    fun copy2DArray(src: Array<LongArray>): Array<LongArray> {
        if (src.isEmpty()) {
            return Array(0) { LongArray(0) }
        }
        return Array(src.size) { src[it].copyOf() }
    }

    /**
     *
     * @param array the array to fill
     * @param theValue the supplier of the value
     */
    @JvmStatic
    @Suppress("unused")
    fun fill(array: DoubleArray, theValue: GetValueIfc = ConstantRV.ZERO) {
        for (i in array.indices) {
            array[i] = theValue.value()
        }
    }

    /**
     *
     * @param array the array to fill
     * @param theValue the supplier of the value
     */
    @JvmStatic
    @Suppress("unused")
    fun fill(array: Array<DoubleArray>, theValue: GetValueIfc = ConstantRV.ZERO) {
        for (doubles in array) {
            fill(doubles, theValue)
        }
    }

    /**
     * The destination array is mutated by this method
     *
     * @param col  the column in the destination to fill
     * @param src  the source for filling the column
     * @param dest the destination array, assumed to be rectangular
     */
    @JvmStatic
    @Suppress("unused")
    fun fillColumn(col: Int, src: DoubleArray, dest: Array<DoubleArray>) {
        require(dest.size == src.size) { "The source array length and destination array must have the same number of rows" }
        require(isRectangular(dest)) { "The matrix was not rectangular" }
        for (i in src.indices) {
            dest[i][col] = src[i]
        }
    }

    /**
     * The array must not be null.
     *
     * @param array the input array. Cannot be empty.
     * @return the sum of the squares for the elements of the array
     */
    @JvmStatic
    @Suppress("unused")
    fun sumOfSquares(array: DoubleArray): Double {
        require(array.isNotEmpty()) { "The array cannot be empty." }
        var sum = 0.0
        for (v in array) {
            sum = sum + v * v
        }
        return sum
    }

    /**
     * Subtracts the arrays element by element. Arrays must have the same length and must not be empty.
     * Computes the sum of the squares forthe differences.
     *
     * @param a the first array
     * @param b the second array
     * @return the sum of  (a[i]-b[i])^2 for the elements
     */
    @JvmStatic
    @Suppress("unused")
    fun sumOfSquaredError(a: DoubleArray, b: DoubleArray): Double {
        require(a.isNotEmpty() && b.isNotEmpty()) { "The arrays cannot be empty." }
        val d = subtractElements(a, b)
        return sumOfSquares(d)
    }

    /**
     * Subtracts the arrays element by element. Arrays must have the same length and must not be empty.
     * Computes the average of the squares forthe differences.
     *
     * @param a the first array. Cannot be empty.
     * @param b the second array. Cannot be empty
     * @return the average of sum of (a[i]-b[i])^2 for the elements
     */
    @JvmStatic
    @Suppress("unused")
    fun meanSquaredError(a: DoubleArray, b: DoubleArray): Double {
        require(a.isNotEmpty() && b.isNotEmpty()) { "The arrays cannot be empty." }
        return sumOfSquaredError(a, b) / a.size
    }

    /**
     * The array must have non-negative elements and not be empty
     *
     * @param array the input array
     * @return the sum of the square roots for the elements of the array
     */
    @JvmStatic
    @Suppress("unused")
    fun sumOfSquareRoots(array: DoubleArray): Double {
        require(array.isNotEmpty()) { "The array cannot be empty." }
        var sum = 0.0
        for (v in array) {
            sum = sum + sqrt(v)
        }
        return sum
    }

    /**
     * Adds the two arrays element by element. Arrays must have the same length and must not be null.
     *
     * @param a the first array
     * @param b the second array
     * @return the array containing a[i]+b[i]
     */
    @JvmStatic
    @Suppress("unused")
    fun addElements(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "The array lengths must match" }
        val c = DoubleArray(a.size)
        for (i in a.indices) {
            c[i] = a[i] + b[i]
        }
        return c
    }

    /**
     * Subtracts the arrays element by element. Arrays must have the same length and must not be null.
     *
     * @param a the first array
     * @param b the second array
     * @return the new array containing a[i]-b[i]
     */
    @JvmStatic
    @Suppress("unused")
    fun subtractElements(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "The array lengths must match" }
        val c = DoubleArray(a.size)
        for (i in a.indices) {
            c[i] = a[i] - b[i]
        }
        return c
    }

    /**
     * Returns a list of the elements that are of the same type as the target
     * class.
     * Usage: getElements(objects, String.class);
     *
     * @param <T>         the type of the element to search for
     * @param objects     the list that can hold anything
     * @param targetClass the class type to find in the list and should be same as
     * T
     * @return a list that holds the items of the targetClass
    </T> */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T> getElements(objects: List<*>, targetClass: Class<T>): List<T> {
        //TODO review, remove dependence on java Class
        val stuff: MutableList<T> = ArrayList()
        for (obj in objects) {
            if (targetClass.isInstance(obj)) {
                val temp = obj as T
                stuff.add(temp)
            }
        }
        return stuff
    }

    /**
     * Returns a count of the elements that are of the same type as the target
     * class.
     *
     * @param objects     the list that can hold anything
     * @param targetClass the class type to find in the list and should be same as
     * T
     * @return a list that holds the items of the targetClass
     */
    @JvmStatic
    @Suppress("unused")
    fun countElements(objects: List<*>, targetClass: Class<*>): Int {
        //TODO review, remove dependence on java Class
        var n = 0
        for (obj in objects) {
            if (targetClass.isInstance(obj)) {
                n++
            }
        }
        return n
    }

    /**
     * @param first  the first array
     * @param second the second array
     * @return true if all elements are equal
     */
    @JvmStatic
    @Suppress("unused")
    fun compareArrays(first: DoubleArray, second: DoubleArray): Boolean {
        if (first.size != second.size) {
            return false
        }
        val flag = true
        for (i in first.indices) {
            if (first[i] != second[i]) {
                return false
            }
        }
        return flag
    }

    /**
     * Converts any null values to replaceNull. For Array<Double> use toDoubleArray()
     *
     * @param array the array to copy
     * @param replaceNull the value to replace any nulls
     * @return the primitive array
     */
    @JvmStatic
    @Suppress("unused")
    fun toPrimitives(array: Array<Double?>, replaceNull: Double = 0.0): DoubleArray {
        if (array.isEmpty()) {
            return DoubleArray(0)
        }
        return DoubleArray(array.size) { array[it] ?: replaceNull }
    }

    /**
     * Converts any nulls to replaceNull. For List<Double> use toDoubleArray()
     *
     * @param doubleList the list to convert
     * @param replaceNull the value to replace any nulls
     * @return the primitive array
     */
    @JvmStatic
    @Suppress("unused")
    fun toPrimitives(doubleList: List<Double?>, replaceNull: Double = 0.0): DoubleArray {
        if (doubleList.isEmpty()) {
            return DoubleArray(0)
        }
        return DoubleArray(doubleList.size) { doubleList[it] ?: replaceNull }
    }

    /**
     * Converts any null values to replaceNull, for Array<Int> use toIntArray()
     *
     * @param array the array to copy
     * @param replaceNull the value to replace any nulls
     * @return the primitive array
     */
    @JvmStatic
    @Suppress("unused")
    fun toPrimitives(array: Array<Int?>, replaceNull: Int = 0): IntArray {
        if (array.isEmpty()) {
            return IntArray(0)
        }
        return IntArray(array.size) { array[it] ?: replaceNull }
    }

    /**
     * Converts any nulls to zero, for List<Int> use toIntArray()
     *
     * @param list the list to convert
     * @param replaceNull the value to replace any nulls
     * @return the primitive array
     */
    @JvmStatic
    @Suppress("unused")
    fun toPrimitives(list: List<Int?>, replaceNull: Int = 0): IntArray {
        if (list.isEmpty()) {
            return IntArray(0)
        }
        return IntArray(list.size) { list[it] ?: replaceNull }
    }

    /**
     * Converts any null values to replaceNull, for Array<Long> use toLongArray()
     *
     * @param array the array to copy
     * @param replaceNull the value to replace any nulls
     * @return the primitive array
     */
    @JvmStatic
    @Suppress("unused")
    fun toPrimitives(array: Array<Long?>, replaceNull: Long = 0): LongArray {
        if (array.isEmpty()) {
            return LongArray(0)
        }
        return LongArray(array.size) { array[it] ?: replaceNull }
    }

    /**
     * Converts any nulls to replaceNull
     *
     * @param list the list to convert
     * @param replaceNull the value to replace any nulls
     * @return the primitive array
     */
    @JvmStatic
    @Suppress("unused")
    fun toPrimitives(list: List<Long?>, replaceNull: Long = 0): LongArray {
        if (list.isEmpty()) {
            return LongArray(0)
        }
        return LongArray(list.size) { list[it] ?: replaceNull }
    }

    /**
     * Convert the array of doubles to an array of strings with each element the
     * corresponding value
     *
     * @param array the array of doubles
     * @param df the decimal format to apply to each element
     * @return the array of strings representing the values of the doubles
     */
    @JvmStatic
    @Suppress("unused")
    fun toStrings(array: DoubleArray, df: DecimalFormat? = null): Array<String> {
        if (array.isEmpty()) {
            return emptyArray()
        }
        return Array(array.size) { df?.format(array[it]) ?: array[it].toString() }
    }

    /**
     * @param array the array to convert
     * @return a comma-delimited string of the array, if empty, returns the empty string
     */
    @JvmStatic
    @Suppress("unused")
    fun toCSVString(array: DoubleArray): String {
        if (array.isEmpty()) {
            return ""
        }
        return array.joinToString()
    }

    /**
     * @param array the array to convert
     * @return a comma-delimited string of the array, if empty or null, returns the empty string
     */
    @JvmStatic
    @Suppress("unused")
    fun toCSVString(array: IntArray): String {
        if (array.isEmpty()) {
            return ""
        }
        return array.joinToString()
    }

    /**
     * @param array the array to convert
     * @return a comma-delimited string of the array, if empty or null, returns the empty string
     */
    @JvmStatic
    @Suppress("unused")
    fun toCSVString(array: LongArray): String {
        if (array.isEmpty()) {
            return ""
        }
        return array.joinToString()
    }

    /**
     * Convert the array of int to an array of double with each element the
     * corresponding value
     *
     * @param array the array of ints
     * @return the array of doubles representing the values of the ints
     */
    @JvmStatic
    @Suppress("unused")
    fun toDoubles(array: IntArray): DoubleArray {
        if (array.isEmpty()) {
            return DoubleArray(0)
        }
        return DoubleArray(array.size) { array[it].toDouble() }
    }

    /**
     * Convert the array of int to an array of double with each element the
     * corresponding value
     *
     * @param array the array of ints
     * @return the array of doubles representing the values of the ints
     */
    @JvmStatic
    @Suppress("unused")
    fun toDoubles(array: Array<Int>): DoubleArray {
        if (array.isEmpty()) {
            return DoubleArray(0)
        }
        return DoubleArray(array.size) { array[it].toDouble() }
    }

    /**
     * Convert the array of long to an array of double with each element the
     * corresponding value
     *
     * @param array the array of longs
     * @return the array of doubles representing the values of the longs
     */
    @JvmStatic
    @Suppress("unused")
    fun toDoubles(array: LongArray): DoubleArray {
        if (array.isEmpty()) {
            return DoubleArray(0)
        }
        return DoubleArray(array.size) { array[it].toDouble() }
    }

    /**
     * Convert the array of long to an array of double with each element the
     * corresponding value
     *
     * @param array the array of longs
     * @return the array of doubles representing the values of the longs
     */
    @JvmStatic
    @Suppress("unused")
    fun toDoubles(array: Array<Long>): DoubleArray {
        if (array.isEmpty()) {
            return DoubleArray(0)
        }
        return DoubleArray(array.size) { array[it].toDouble() }
    }

    /**
     * Convert the 2D array of double to a 2D array of Double with each element the
     * corresponding value
     *
     * @param array the array of doubles
     * @return the array of Doubles representing the values of the doubles
     */
    @JvmStatic
    @Suppress("unused")
    fun toDoubles(array: Array<DoubleArray>): Array<Array<Double>> {
        if (array.isEmpty()) {
            return Array(0) { emptyArray() }
        }
        return Array(array.size) { array[it].toTypedArray() }
    }

    /**
     * Convert the 2D array of int to a 2D array of Integer with each element the
     * corresponding value
     *
     * @param array the array of int
     * @return the array of Integer representing the values of the int
     */
    @JvmStatic
    @Suppress("unused")
    fun toInts(array: Array<IntArray>): Array<Array<Int>> {
        if (array.isEmpty()) {
            return Array(0) { emptyArray() }
        }
        return Array(array.size) { array[it].toTypedArray() }
    }

    /**
     *  Converts the list of arrays to an array of arrays.
     */
    @JvmStatic
    @Suppress("unused")
    fun to2DDoubleArray(list: List<DoubleArray>): Array<DoubleArray> {
        return list.toTypedArray()
    }

    /**
     *  Converts the two-dimensional array into a list containing
     *  the arrays.  The rows of the array become the elements of
     *  the list.
     */
    @JvmStatic
    @Suppress("unused")
    fun toDoubleList(twoDArray: Array<DoubleArray>): List<DoubleArray> {
        val list = mutableListOf<DoubleArray>()
        for (array in twoDArray) {
            list.add(array)
        }
        return list
    }

    /**
     * Convert the 2D array of int to a 2D array of Long with each element the
     * corresponding value
     *
     * @param array the array of int
     * @return the array of Integer representing the values of the int
     */
    @JvmStatic
    @Suppress("unused")
    fun toLongs(array: Array<LongArray>): Array<Array<Long>> {
        if (array.isEmpty()) {
            return Array(0) { emptyArray() }
        }
        return Array(array.size) { array[it].toTypedArray() }
    }

    /**
     * Converts the array of strings to Doubles
     *
     * @param dblStrings an array of strings that represent Doubles
     * @param parseFail the value to use if the parse fails or string is null by default Double.NaN
     * @return the parsed doubles as an array
     */
    @JvmStatic
    @Suppress("unused")
    fun parseToDoubles(dblStrings: Array<String>, parseFail: Double = Double.NaN): DoubleArray {
        if (dblStrings.isEmpty()) {
            return DoubleArray(0)
        }
        val target = DoubleArray(dblStrings.size)
        for (i in dblStrings.indices) {
            try {
                target[i] = dblStrings[i].toDouble()
            } catch (e: NumberFormatException) {
                target[i] = parseFail
            }
        }
        return target
    }

    /**
     * Converts the array of strings to Doubles
     *
     * @param dblStrings a list of strings that represent Doubles
     * @param parseFail the value to use if the parse fails or string is null by default Double.NaN
     * @return the parsed doubles as an array
     */
    @JvmStatic
    @Suppress("unused")
    fun parseToDoubles(dblStrings: List<String>, parseFail: Double = Double.NaN): DoubleArray {
        return parseToDoubles(dblStrings.toTypedArray(), parseFail)
    }

    /**
     * Transposes the array returned transpose[x][y] = array[y][x]
     *
     * @param array an array with m rows and n columns
     * @return an array with n columns and m rows
     */
    @JvmStatic
    @Suppress("unused")
    fun transpose(array: Array<IntArray>): Array<IntArray> {
        require(isRectangular(array)) { "The array was not rectangular" }
        val m = array.size
        val n: Int = array[0].size
        val transpose = Array(n) { IntArray(m) }
        for (x in 0 until n) {
            for (y in 0 until m) {
                transpose[x][y] = array[y][x]
            }
        }
        return transpose
    }

    /**
     * Transposes the array returned transpose[x][y] = array[y][x]
     *
     * @param array an array with m rows and n columns
     * @return an array with n columns and m rows
     */
    @JvmStatic
    @Suppress("unused")
    fun transpose(array: Array<DoubleArray>): Array<DoubleArray> {
        require(isRectangular(array)) { "The array was not rectangular" }
        val m = array.size
        val n: Int = array[0].size
        val transpose = Array(n) { DoubleArray(m) }
        for (x in 0 until n) {
            for (y in 0 until m) {
                transpose[x][y] = array[y][x]
            }
        }
        return transpose
    }

    /**
     * Transposes the array returned transpose[x][y] = array[y][x]
     *
     * @param array an array with m rows and n columns
     * @return an array with n columns and m rows
     */
    @JvmStatic
    @Suppress("unused")
    fun transpose(array: Array<LongArray>): Array<LongArray> {
        require(isRectangular(array)) { "The array was not rectangular" }
        val m = array.size
        val n: Int = array[0].size
        val transpose = Array(n) { LongArray(m) }
        for (x in 0 until n) {
            for (y in 0 until m) {
                transpose[x][y] = array[y][x]
            }
        }
        return transpose
    }

    /**
     * Transposes the array returned transpose[x][y] = array[y][x]
     *
     * @param array an array with m rows and n columns, must be rectangular
     * @return an array with n columns and m rows
     */
    @JvmStatic
    @Suppress("unused")
    inline fun <reified T> transpose(array: Array<Array<T>>): Array<Array<T>> {
        require(isRectangular(array)) { "The array was not rectangular" }
        val cols = array[0].size
        val rows = array.size
        return Array(cols) { j ->
            Array(rows) { i ->
                array[i][j]
            }
        }
    }

    /**
     * Each labeled array in the map becomes a row in the returned array, which may be ragged because
     * each row in the array may have a different length.
     *
     * @param labeledRows a map holding named rows of data
     * @return a 2D array, where rows of the array hold the data in the order returned
     * from the string labels.
     */
    @JvmStatic
    @Suppress("unused")
    fun copyToRows(labeledRows: LinkedHashMap<String, DoubleArray>): Array<DoubleArray> {
        return labeledRows.values.toTypedArray()
    }

    /**
     * Each labeled array in the map becomes a column in the returned array. Each array in
     * the map must have the same number of elements.
     *
     * @param labeledColumns a map holding named columns of data
     * @return a 2D array, where columns of the array hold the data in the order returned
     * from the string labels.
     */
    @JvmStatic
    @Suppress("unused")
    fun copyToColumns(labeledColumns: LinkedHashMap<String, DoubleArray>): Array<DoubleArray> {
        if (labeledColumns.isEmpty()) {
            return Array(0) { DoubleArray(0) }
        }
        val data = copyToRows(labeledColumns)
        require(isRectangular(data)) { "The stored arrays do not have the same number of elements" }
        return transpose(data)
    }

    /**
     * Assumes that the entries in the list are string representations of double values.
     * Each String[] can have a different number of elements.  Thus, the returned
     * array may be ragged.
     *
     * @param entries the list of data entries
     * @return the 2D array
     */
    @JvmStatic
    @Suppress("unused")
    fun parseTo2DArray(entries: List<Array<String>>): Array<DoubleArray> {
        val data = mutableListOf<DoubleArray>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val strings = iterator.next()
            val rowData = parseToDoubles(strings)
            data.add(rowData)

        }
        return data.toTypedArray()
    }

    /**
     * @param array the array of objects
     * @param <T>   the type of the objects
     * @return a String array holding the string value of the elements of the array
    </T> */
    @JvmStatic
    @Suppress("unused")
    fun <T> asStringArray(array: Array<T>?): Array<String?> {
        if (array == null) {
            return arrayOfNulls(0)
        }
        val sArray = arrayOfNulls<String>(array.size)
        for (i in sArray.indices) sArray[i] = array[i].toString()
        return sArray
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are strictly increasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isStrictlyIncreasing(array: DoubleArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] >= array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are strictly decreasing a_0 gt a_1 gt a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are strictly increasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isStrictlyDecreasing(array: DoubleArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] <= array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are increasing a_0 lte a_1 lte a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are increasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isIncreasing(array: DoubleArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] > array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are decreasing a_0 gte a_1 gte a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are decreasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isDecreasing(array: DoubleArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] < array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 = a_1 = a_2, etc.
     *
     * @param array the array to check,
     * @param precision the precision to consider things equal, defaults to KSLMath.defaultNumericalPrecision
     * @return true if all elements are equal, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isAllEqual(array: DoubleArray, precision: Double = KSLMath.defaultNumericalPrecision): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (!KSLMath.equal(array[i - 1], array[i], precision)) {
                return false
            }
//            if (array[i - 1] != array[i]) {
//                return false
//            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 != a_1 != a_2, etc.
     *
     * @param array the array to check
     * @param precision the precision to consider things equal, defaults to KSLMath.defaultNumericalPrecision
     * @return true if all elements are different, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isAllDifferent(array: DoubleArray, precision: Double = KSLMath.defaultNumericalPrecision): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        val sorted = array.copyOf().sortedArray()
        for (i in 1 until sorted.size) {
            if (KSLMath.equal(sorted[i - 1], sorted[i], precision)) {
                return false
            }
//            if (array[i - 1] == array[i]) {
//                return false
//            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 != a_1 != a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are different, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isAllDifferent(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        val sorted = array.copyOf().sortedArray()
        for (i in 1 until sorted.size) {
            if (sorted[i - 1] == sorted[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 = a_1 = a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are equal, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isAllEqual(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] != array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if any
     * element is less than or equal to 0.0.
     *
     * @param array the array to check
     * @return true if all are strictly positive
     */
    @JvmStatic
    @Suppress("unused")
    fun isStrictlyPositive(array: DoubleArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        for (x in array) {
            if (x <= 0.0) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are strictly increasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isStrictlyIncreasing(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] >= array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are strictly decreasing a_0 gt a_1 gt a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are strictly increasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isStrictlyDecreasing(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] <= array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are increasing a_0 lte a_1 lte a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are increasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isIncreasing(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] > array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are decreasing a_0 gte a_1 gte a_2, etc.
     *
     * @param array the array to check
     * @return true if all elements are decreasing, if there
     * are 0 elements, then it returns false, 1 element returns true
     */
    @JvmStatic
    @Suppress("unused")
    fun isDecreasing(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] < array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Performs element-wise modulo (%) operator on the array.
     * The array is changed in place.
     *
     * @param array   the array to apply the modulo operator on
     * @param divisor the divisor for each element
     */
    @JvmStatic
    @Suppress("unused")
    fun remainder(array: DoubleArray, divisor: Double) {
        require(divisor != 0.0) { "The divisor cannot be zero!" }
        for (i in array.indices) {
            array[i] = array[i] % divisor
        }
    }

    /**
     * Performs element-wise absolute value on the array.
     * The array is changed in place.
     *
     * @param array the array to apply the absolute value function on
     */
    @JvmStatic
    @Suppress("unused")
    fun abs(array: DoubleArray) {
        for (i in array.indices) {
            array[i] = abs(array[i])
        }
    }

    /**
     * Element-wise application of the supplied function. The
     * array is changed in place. Using FunctionIfc avoids autoboxing
     * when dealing with primitive doubles.
     *
     * @param array    the array to apply the function on
     * @param function the function to apply
     */
    @JvmStatic
    @Suppress("unused")
    fun apply(array: DoubleArray, function: FunctionIfc) {
        for (i in array.indices) {
            array[i] = function.f(array[i])
        }
    }

    /**
     * Element-wise application of the supplied function. The
     * array is changed in place. Using FunctionIfc avoids autoboxing
     * when dealing with primitive doubles.
     *
     * @param array    the array to apply the function on
     * @param function the function to apply
     */
    @JvmStatic
    @Suppress("unused")
    fun apply(array: Array<DoubleArray>, function: FunctionIfc) {
        for (i in array.indices) {
            for (j in 0 until array[i].size) {
                array[i][j] = function.f(array[i][j])
            }
        }
    }

    /**
     * Checks if any element of the array is equal to Double.NaN
     *
     * @param array the array to check
     * @return true if any element of the array is NaN
     */
    @JvmStatic
    @Suppress("unused")
    fun checkForNaN(array: DoubleArray): Boolean {
        for (x in array) {
            if (x.isNaN()) {
                return true
            }
        }
        return false
    }

    /**
     * @param array    the array to process
     * @param interval the interval
     * @return an array containing the array values that are contained in the interval
     */
    @JvmStatic
    @Suppress("unused")
    fun dataInInterval(array: DoubleArray, interval: Interval): DoubleArray {
        val saver = DoubleArraySaver()
        for (x in array) {
            if (interval.contains(x)) {
                saver.save(x)
            }
        }
        return saver.savedData()
    }

    // contributed by Andrew Gibson
    /**
     * contributed by Andrew Gibson
     * simple way to create a n-element vector of the same value (x)
     *
     * @param x - scalar input value
     * @param n - number of replications
     * @return - 1D array of length n filled with values x
     */
    @JvmStatic
    @Suppress("unused")
    fun replicate(x: Double, n: Int): DoubleArray {
        require(n >= 0) { "n cannot be negative" }
        val res = DoubleArray(n)
        Arrays.fill(res, x)
        return res
    }

    /**
     * contributed by Andrew Gibson
     * round the 1D array x  to a multiple of granularity (double[])
     * note that 0 or null granularity values are interpreted as "no rounding"
     *
     * @param x           - the input
     * @param granularity - the granularity to which to round x
     * @return - 1 1D array of elements i s.t. x[i] is rounded to granularity[i]
     */
    @JvmStatic
    @Suppress("unused")
    fun mround(x: DoubleArray, granularity: DoubleArray?): DoubleArray {
        return if (granularity == null) {
            x
        } else {
            require(x.size == granularity.size) { "x array and granularity array have different lengths" }
            val res = DoubleArray(x.size)
            for (i in x.indices) {
                res[i] = KSLMath.mround(x[i], granularity[i])
            }
            res
        }
    }

    /**
     * contributed by Andrew Gibson
     * round a 1D array x to a multiple of a scalar granularity value
     * note that 0 or null granularity values are interpreted as "no rounding"
     *
     * Granularity represents the finest division of the measurement scale.
     * For example, a 12-inch rule that has inches divided into 4 quarters has
     * a granularity of 1/4 or 0.25. The function rounds the supplied double
     * to the nearest multiple of the granularity.
     *
     * For example,
     *
     * mround(3.1459, granularity = 0.25) = 3.25
     * mround(3.0459, granularity = 0.25) = 3.0
     *
     * See this stack overflow [post](https://stackoverflow.com/questions/10540341/java-function-to-preserve-the-granularity)
     * for further information.
     * @param x           - input[]
     * @param granularity - Double
     * @return - 1D array the same size as x
     */
    @JvmStatic
    @Suppress("unused")
    fun mround(x: DoubleArray, granularity: Double): DoubleArray {
        val gr = DoubleArray(x.size)
        Arrays.fill(gr, granularity)
        return mround(x, gr)
    }

    /**
     * contributed by Andrew Gibson
     * calculate the number of decimal places needed to
     * give AT LEAST sf digits to all values
     *
     * @param values - double array
     * @param sf     - number of significant figures
     * @return the number of decimal places
     */
    @JvmStatic
    @Suppress("unused")
    fun sigFigDecimals(values: DoubleArray, sf: Int): Int {
        val p = IntArray(values.size)
        for (i in values.indices) {
            p[i] = sigFigDecimals(values[i], sf)
        }
        return if (p.isNotEmpty()) {
            p.max()
        } else {
            0
        }
    }

    /**
     * contributed by Andrew Gibson
     * calculate the number of decimal places needed to
     * give sf digits
     *
     * @param value - double value
     * @param sf    -
     * @return the number of decimal places
     */
    @JvmStatic
    @Suppress("unused")
    fun sigFigDecimals(value: Double, sf: Int): Int {
        // handle 0 (which requires no sigfigs) and for
        // which log(0) is -Inf
        if (value.compareTo(0.0) == 0) return 0
        var p = floor(log10(abs(value))).toInt()
        p = max(0, sf - p - 1)
        return p
    }

    /**
     * contributed by Andrew Gibson
     *
     * @param value - double value
     * @param sf    -
     * @return the value formatted as a String
     */
    @JvmStatic
    @Suppress("unused")
    fun sigFigFormat(value: Double, sf: Int): String {
        val p = sigFigDecimals(value, sf)
        return String.format("%,." + p + "f", value)
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points less than or equal to x
     */
    @JvmStatic
    @Suppress("unused")
    fun countLessEqualTo(data: DoubleArray, x: Double): Int {
        var cnt = 0
        for (datum in data) {
            if (datum <= x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points less than x
     */
    @JvmStatic
    @Suppress("unused")
    fun countLessThan(data: DoubleArray, x: Double): Int {
        var cnt = 0
        for (datum in data) {
            if (datum < x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points greater than or equal to x
     */
    @JvmStatic
    @Suppress("unused")
    fun countGreaterEqualTo(data: DoubleArray, x: Double): Int {
        var cnt = 0
        for (datum in data) {
            if (datum >= x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points greater than x
     */
    @JvmStatic
    @Suppress("unused")
    fun countGreaterThan(data: DoubleArray, x: Double): Int {
        var cnt = 0
        for (datum in data) {
            if (datum > x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points less than or equal to x
     */
    @JvmStatic
    @Suppress("unused")
    fun countLessEqualTo(data: IntArray, x: Int): Int {
        var cnt = 0
        for (datum in data) {
            if (datum <= x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points less than x
     */
    @JvmStatic
    @Suppress("unused")
    fun countLessThan(data: IntArray, x: Int): Int {
        var cnt = 0
        for (datum in data) {
            if (datum < x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points greater than or equal to x
     */
    @JvmStatic
    @Suppress("unused")
    fun countGreaterEqualTo(data: IntArray, x: Int): Int {
        var cnt = 0
        for (datum in data) {
            if (datum >= x) {
                cnt++
            }
        }
        return cnt
    }

    /**
     * @param data the data to count
     * @param x    the ordinate to check
     * @return the number of data points greater than x
     */
    @JvmStatic
    @Suppress("unused")
    fun countGreaterThan(data: IntArray, x: Int): Int {
        var cnt = 0
        for (datum in data) {
            if (datum > x) {
                cnt++
            }
        }
        return cnt
    }


    /**
     * @param data the data to sort
     * @return a copy of the sorted array in ascending order representing the order statistics
     */
    @JvmStatic
    @Suppress("unused")
    fun orderStatistics(data: DoubleArray): DoubleArray {
        val doubles = data.copyOf()
        doubles.sort()
        return doubles
    }

    /**
     * Constructs an array that holds the indices of the items in the data in their sort order.
     * Example: If the array is [2,3,1,4,5], then [2,0,1,3,4] is returned. Recall that indices
     * are zero-based.
     *
     * @param data the data to sort
     * @return the indices of the original items indicating the sort order
     */
    @JvmStatic
    @Suppress("unused")
    fun sortedIndices(data: DoubleArray) : IntArray {
        // Pair each element with its original index
        val indexedArray = data.mapIndexed { index, value -> Pair(value, index) }
        // Sort the pairs based on the element's value
        val sortedIndexedArray = indexedArray.sortedBy { it.first }
        // Extract the original indices from the sorted pairs
        return sortedIndexedArray.map { it.second }.toIntArray()
    }

    /**
     *  Returns a new array with duplicate data values removed from the original array,
     *  preserving the order of the observations.
     */
    @JvmStatic
    @Suppress("unused")
    fun removeDuplicates(data: DoubleArray): DoubleArray {
        val doubles = data.copyOf()
        val set = doubles.toSet()
        return set.toTypedArray().toDoubleArray()
    }

    /**
     *  Returns a new array in the same order as the original array but
     *  with the specified value removed.  All instances of the value
     *  will be removed.
     */
    @JvmStatic
    @Suppress("unused")
    fun removeValue(data: DoubleArray, value: Double): DoubleArray {
        val values = mutableListOf<Double>()
        for (x in data) {
            if (x != value) {
                values.add(x)
            }
        }
        return values.toDoubleArray()
    }

    /**
     * Returns a statistic that summarizes the passed in array of values
     *
     * @param x the values to compute statistics for
     * @return a Statistic summarizing the data
     */
    @JvmStatic
    @Suppress("unused")
    fun statistics(x: DoubleArray): Statistic {
        val s = Statistic()
        s.collect(x)
        return s
    }

    /**
     * Returns a BoxPlotSummary that summarizes the passed in array of values
     *
     * @param x the values to compute statistics for
     * @return a BoxPlotSummary summarizing the data
     */
    @JvmStatic
    @Suppress("unused")
    fun boxPlotSummary(x: DoubleArray): BoxPlotSummary {
        return BoxPlotSummary(x)
    }

    /**
     * Creates a matrix of Doubles with [nRows] and [nCols] containing the
     * supplied [value]
     */
    @JvmStatic
    @Suppress("unused")
    fun matrixOfDoubles(nRows: Int, nCols: Int, value: Double = 0.0): Array<DoubleArray> {
        require(nRows > 0) { "The number of rows must be >= 1" }
        require(nCols > 0) { "The number of columns must be >= 1" }
        return Array(nRows) {
            DoubleArray(nCols) { value }
        }
    }

    /**
     * Creates a matrix of Ints with [nRows] and [nCols] containing the
     * supplied [value]
     */
    @JvmStatic
    @Suppress("unused")
    fun matrixOfInts(nRows: Int, nCols: Int, value: Int = 0): Array<IntArray> {
        require(nRows > 0) { "The number of rows must be >= 1" }
        require(nCols > 0) { "The number of columns must be >= 1" }
        return Array(nRows) {
            IntArray(nCols) { value }
        }
    }

    /**
     * Creates a matrix of Longs with [nRows] and [nCols] containing the
     * supplied [value]
     */
    @JvmStatic
    @Suppress("unused")
    fun matrixOfLongs(nRows: Int, nCols: Int, value: Long = 0): Array<LongArray> {
        require(nRows > 0) { "The number of rows must be >= 1" }
        require(nCols > 0) { "The number of columns must be >= 1" }
        return Array(nRows) {
            LongArray(nCols) { value }
        }
    }

    /**
     * Creates a matrix of doubles with [nRows] and [nCols] containing values from the
     * supplied [x]
     */
    @JvmStatic
    @Suppress("unused")
    fun matrixOfDoubles(nRows: Int, nCols: Int, x: GetValueIfc): Array<DoubleArray> {
        require(nRows > 0) { "The number of rows must be >= 1" }
        require(nCols > 0) { "The number of columns must be >= 1" }
        return Array(nRows) {
            DoubleArray(nCols) { x.value }
        }
    }

    /**
     *  Computes the difference, (d[i] = x[i+k] - x[i]) for i = 0 until x.size - k
     *  This is the discrete difference operator.  For example, if k = 1, then
     *  d[0] = x[1] - x[0], d[1] = x[2] - x[1], ..., d[x.size - 2] = x[x.size -1 ]- x[x.size -2]
     *  and returns the new array of differences.
     */
    @JvmStatic
    @Suppress("unused")
    fun diff(x: DoubleArray, k: Int = 1): DoubleArray {
        require(k >= 1) { "The differencing delta must be >= 1" }
        return DoubleArray(x.size - k) { x[it + k] - x[it] }
    }

    /**
     * Returns a new array of size (x.size -k) that is lagged by k elements
     * y[i] = x[i+k] for i=0, 1,...
     */
    @JvmStatic
    @Suppress("unused")
    fun lag(x: DoubleArray, k: Int = 1): DoubleArray {
        require(k >= 1) { "The lag must be >= 1" }
        return DoubleArray(x.size - k) { x[it + k] }
    }

    /**
     * Returns a new array of size (x.size -k) that is lagged by k elements
     * y[i] = x[i+k] for i=0, 1,...
     */
    @JvmStatic
    @Suppress("unused")
    inline fun <reified T> lag(x: Array<T>, k: Int = 1): Array<T> {
        require(k >= 1) { "The lag must be >= 1" }
        return Array(x.size - k) { x[it + k] }
    }

    /**
     *  Computes the cartesian product of the two arrays. Returns
     *  a list of pairs where the first element of the pair is from the
     *  [first] array and the [second] element of the pair is from the second
     *  array. This produces all possible combinations of the elements
     *  as the pairs. If the first array has n elements and the second
     *  array has m elements, then the number of pairs produced is n x m.
     *
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesian(first: DoubleArray, second: DoubleArray): List<Pair<Double, Double>> {
        val list = mutableListOf<Pair<Double, Double>>()
        for (x in first) {
            for (y in second) {
                list.add(Pair(x, y))
            }
        }
        return list
    }

    /**
     *  Computes the cartesian product of the two collections. Returns
     *  a list of pairs where the first element of the pair is from the
     *  [first] collection and the [second] element of the pair is from the second
     *  collection. This produces all possible combinations of the elements
     *  as the pairs. If the first collection has n elements and the second
     *  collection has m elements, then the number of pairs produced is n x m.
     *
     */
    @JvmStatic
    @Suppress("unused")
    fun <F, S> cartesian(first: Collection<F>, second: Collection<S>): List<Pair<F, S>> {
        val list = mutableListOf<Pair<F, S>>()
        for (x in first) {
            for (y in second) {
                list.add(Pair(x, y))
            }
        }
        return list
    }

    /**
     *  Returns a new array with the [value] inserted at the index.
     */
    @JvmStatic
    @Suppress("unused")
    fun insertAt(arr: IntArray, value: Int, index: Int): IntArray {
        val result = IntArray(arr.size + 1)
        if (index >= arr.size) {
            // past the end of the original array, copy it all
            arr.copyInto(result)
            result[result.lastIndex] = value
            return result
        }
        arr.copyInto(result, 0, 0, index)
        result[index] = value
        arr.copyInto(result, index + 1, index, arr.size - index)
        return result
    }

    /**
     *  Returns a new array with the [value] inserted at the index.
     */
    @JvmStatic
    @Suppress("unused")
    fun insertAt(arr: DoubleArray, value: Double, index: Int): DoubleArray {
        val result = DoubleArray(arr.size + 1)
        if (index >= arr.size) {
            // past the end of the original array, copy it all
            arr.copyInto(result)
            result[result.lastIndex] = value
            return result
        }
        arr.copyInto(result, 0, 0, index)
        result[index] = value
        arr.copyInto(result, index + 1, index, arr.size - index)
        return result
    }

    /**
     *  Removes the element at the index. If the index is out
     *  of bounds, then a copy of the array is returned.
     */
    @JvmStatic
    @Suppress("unused")
    fun removeAt(arr: IntArray, index: Int): IntArray {
        if (index < 0 || index >= arr.size) {
            return arr.copyOf()
        }
        val result = arr.toMutableList()
        result.removeAt(index)
        return result.toIntArray()
    }

    /**
     *  Removes the element at the index. If the index is out
     *  of bounds, then a copy of the array is returned.
     */
    @JvmStatic
    @Suppress("unused")
    fun removeAt(arr: DoubleArray, index: Int): DoubleArray {
        if (index < 0 || index >= arr.size) {
            return arr.copyOf()
        }
        val result = arr.toMutableList()
        result.removeAt(index)
        return result.toDoubleArray()
    }

    /**
     *  The rows of the [sets] array are treated like elements in sets.
     *  The function returns the element stored at the [index] of
     *  the cartesian product of the sets. The index must be between
     *  0 and (n-1), where n is the number of elements in the cartesian
     *  product. The elements start at 0. Since the elements of the arrays
     *  represent a set, the values must be unique. That is, no duplicates
     *  are permitted within an individual array.
     *
     *  Example:
     *      val a = intArrayOf(1, 2)
     *     val b = intArrayOf(3, 4)
     *     val c = intArrayOf(5)
     *     val d = intArrayOf(6, 7, 8)
     *     val index = 4
     *     val result = cartesianProductRow(array, index)
     *     println("The element at index $index is: ${result.joinToString()}")
     *
     *     Prints:
     *     The element at index 4 is: 1, 4, 5, 7
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProductRow(sets: Array<IntArray>, index: Int): IntArray {
        var n = 1
        for (i in sets.indices) {
            require(sets[i].isAllDifferent()) { "The elements of of the ${i}th array were not all unique" }
            n = n * sets[i].size
        }
        require(index in 0..<n) { "The supplied index must be between 0 and ${n - 1}" }
        var k = index
        var currentElement: Int
        var currentSetLength: Int
        val totalSets = sets.size
        val resultTuple = IntArray(totalSets)
        for (i in totalSets - 1 downTo 0) {
            currentSetLength = sets[i].size
            currentElement = sets[i][k % currentSetLength]
            resultTuple[i] = currentElement
            k = k / currentSetLength
        }
        return resultTuple
    }

    /**
     *  The rows of the [sets] array are treated like elements in sets.
     *  The function returns the element stored at the [index] of
     *  the cartesian product of the sets. The index must be between
     *  0 and (n-1), where n is the number of elements in the cartesian
     *  product. The elements start at 0. Since the elements of the arrays
     *  represent a set, the values must be unique. That is, no duplicates
     *  are permitted within an individual array.
     *
     *  Example:
     *      val a = intArrayOf(1, 2)
     *     val b = intArrayOf(3, 4)
     *     val c = intArrayOf(5)
     *     val d = intArrayOf(6, 7, 8)
     *     val index = 4
     *     val result = cartesianProductRow(array, index)
     *     println("The element at index $index is: ${result.joinToString()}")
     *
     *     Prints:
     *     The element at index 4 is: 1, 4, 5, 7
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProductRow(sets: List<IntArray>, index: Int): IntArray {
        var n = 1
        for (i in sets.indices) {
            require(sets[i].isAllDifferent()) { "The elements of of the ${i}th array were not all unique" }
            n = n * sets[i].size
        }
        require(index in 0..<n) { "The supplied index must be between 0 and ${n - 1}" }
        var k = index
        var currentElement: Int
        var currentSetLength: Int
        val totalSets = sets.size
        val resultTuple = IntArray(totalSets)
        for (i in totalSets - 1 downTo 0) {
            currentSetLength = sets[i].size
            currentElement = sets[i][k % currentSetLength]
            resultTuple[i] = currentElement
            k = k / currentSetLength
        }
        return resultTuple
    }

    /**
     *  The rows of the [sets] array are treated like elements in sets.
     *  The function returns the element stored at the [index] of
     *  the cartesian product of the sets. The index must be between
     *  0 and (n-1), where n is the number of elements in the cartesian
     *  product. The elements start at 0. Since the elements of the arrays
     *  represent a set, the values must be unique. That is, no duplicates
     *  are permitted within an individual array.
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProductRow(sets: Array<DoubleArray>, index: Int): DoubleArray {
        var n = 1
        for (i in sets.indices) {
            require(sets[i].isAllDifferent()) { "The elements of of the ${i}th array were not all unique" }
            n = n * sets[i].size
        }
        require(index in 0..<n) { "The supplied index must be between 0 and ${n - 1}" }
        var k = index
        var currentElement: Double
        var currentSetLength: Int
        val totalSets = sets.size
        val resultTuple = DoubleArray(totalSets)
        for (i in totalSets - 1 downTo 0) {
            currentSetLength = sets[i].size
            currentElement = sets[i][k % currentSetLength]
            resultTuple[i] = currentElement
            k = k / currentSetLength
        }
        return resultTuple
    }

    /**
     *  The rows of the [sets] array are treated like elements in sets.
     *  The function returns the element stored at the [index] of
     *  the cartesian product of the sets. The index must be between
     *  0 and (n-1), where n is the number of elements in the cartesian
     *  product. The elements start at 0. Since the elements of the arrays
     *  represent a set, the values must be unique. That is, no duplicates
     *  are permitted within an individual array.
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProductRow(sets: List<DoubleArray>, index: Int): DoubleArray {
        var n = 1
        for (i in sets.indices) {
            require(sets[i].isAllDifferent()) { "The elements of of the ${i}th array were not all unique" }
            n = n * sets[i].size
        }
        require(index in 0..<n) { "The supplied index must be between 0 and ${n - 1}" }
        var k = index
        var currentElement: Double
        var currentSetLength: Int
        val totalSets = sets.size
        val resultTuple = DoubleArray(totalSets)
        for (i in totalSets - 1 downTo 0) {
            currentSetLength = sets[i].size
            currentElement = sets[i][k % currentSetLength]
            resultTuple[i] = currentElement
            k = k / currentSetLength
        }
        return resultTuple
    }

    /**
     *  Computes the cartesian product of the supplied sets and returns
     *  a list holding the rows of the cartesian product
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProduct(a: Set<*>, b: Set<*>, vararg sets: Set<*>): List<List<*>> =
        (setOf(a, b).plus(sets))
            .fold(listOf(listOf<Any?>())) { acc, set ->
                acc.flatMap { list -> set.map { element -> list + element } }
            }

    /**
     *  Computes the cartesian product of the sets of doubles and returns
     *  a list holding the rows of the cartesian product with each row represented
     *  as a list.
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProductOfDoubles(a: Set<Double>, b: Set<Double>, vararg sets: Set<Double>): List<List<Double>> =
        (setOf(a, b).plus(sets))
            .fold(listOf(listOf())) { acc, set ->
                acc.flatMap { list -> set.map { element -> list + element } }
            }

    /**
     *  Computes the cartesian product of the sets of ints and returns
     *  a list holding the rows of the cartesian product with each row represented
     *  as a list.
     */
    @JvmStatic
    @Suppress("unused")
    fun cartesianProductOfInts(a: Set<Int>, b: Set<Int>, vararg sets: Set<Int>): List<List<Int>> =
        (setOf(a, b).plus(sets))
            .fold(listOf(listOf())) { acc, set ->
                acc.flatMap { list -> set.map { element -> list + element } }
            }

    /**
     *  Computes the Manhattan distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun manhattanDistance(a: List<Int>, b: List<Int>): Int {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var sum = 0
        for (i in a.indices) {
            sum = sum + abs(a[i] * b[i])
        }
        return sum
    }

    /**
     *  Computes the Manhattan distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun manhattanDistance(a: IntArray, b: IntArray): Int {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var sum = 0
        for (i in a.indices) {
            sum = sum + abs(a[i] * b[i])
        }
        return sum
    }

    /**
     *  Computes the Manhattan distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun manhattanDistance(a: List<Double>, b: List<Double>): Double {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var sum = 0.0
        for (i in a.indices) {
            sum = sum + abs(a[i] * b[i])
        }
        return sum
    }

    /**
     *  Computes the Manhattan distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun manhattanDistance(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var sum = 0.0
        for (i in a.indices) {
            sum = sum + abs(a[i] * b[i])
        }
        return sum
    }

    /**
     *  Computes the Chebyshev distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun chebyshevDistance(a: IntArray, b: IntArray): Int {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var maxDiff = 0
        for (i in a.indices) {
            val diff = abs(a[i] - b[i])
            if (diff > maxDiff) {
                maxDiff = diff
            }
        }
        return maxDiff
    }

    /**
     *  Computes the Chebyshev distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun chebyshevDistance(a: List<Int>, b: List<Int>): Int {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var maxDiff = 0
        for (i in a.indices) {
            val diff = abs(a[i] - b[i])
            if (diff > maxDiff) {
                maxDiff = diff
            }
        }
        return maxDiff
    }

    /**
     *  Computes the Chebyshev distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun chebyshevDistance(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var maxDiff = 0.0
        for (i in a.indices) {
            val diff = abs(a[i] - b[i])
            if (diff > maxDiff) {
                maxDiff = diff
            }
        }
        return maxDiff
    }

    /**
     *  Computes the Chebyshev distance between the two vectors.
     *  The vectors must be of the same size.
     */
    @JvmStatic
    @Suppress("unused")
    fun chebyshevDistance(a: List<Double>, b: List<Double>): Double {
        require(a.size == b.size) { "Vectors must be of the same length" }
        var maxDiff = 0.0
        for (i in a.indices) {
            val diff = abs(a[i] - b[i])
            if (diff > maxDiff) {
                maxDiff = diff
            }
        }
        return maxDiff
    }

}

/** Extension functions and other functions for working with arrays
 * @author rossetti@uark.edu
 */

@Suppress("unused")
inline fun <reified T> matrixOfNulls(n: Int, m: Int): Array<Array<T?>> = Array(n) { arrayOfNulls<T>(m) }

inline fun <reified T> to2DArray(lists: List<List<T>>): Array<Array<T>> {
    return Array(lists.size) { row -> lists[row].toTypedArray() }
}

/**
 *  Computes the difference, (d[i] = x[i+k] - x[i]) for i = 0 until x.size - k
 *  This is the discrete difference operator.  For example, if k = 1, then
 *  d[0] = x[1] - x[0], d[1] = x[2] - x[1], ..., d[x.size - 2] = x[x.size -1 ]- x[x.size -2]
 *  and returns the new array of differences.
 */
@Suppress("unused")
fun DoubleArray.diff(k: Int = 1): DoubleArray {
    return KSLArrays.diff(this, k)
}

/**
 * Returns a new array of size (x.size -k) that is lagged by k elements
 * y[i] = x[i+k] for i=0,1,...
 */
@Suppress("unused")
fun DoubleArray.lag(k: Int = 1): DoubleArray {
    return KSLArrays.lag(this, k)
}

/**
 * Returns a statistic that summarizes the array of values
 *
 * @return a Statistic summarizing the data
 */
@Suppress("unused")
fun DoubleArray.statistics(): Statistic {
    return KSLArrays.statistics(this)
}

/**
 *  Returns a statistic that summarizes the data in the collection.
 */
@Suppress("unused")
fun Collection<Double>.statistics(): Statistic {
    return this.toDoubleArray().statistics()
}

/** Takes an array of length, n, and computes k batch means where each batch mean
 * is the average of batchSize (b) elements such that b = Math.FloorDiv(n, k).
 * If the number of batches, k, does not divide evenly into n, then n - (k*b) observations are not processed
 * at the end of the array.
 *
 * The batch means are contained in the returned array.
 *
 * @param numBatches the number of batches (k), must be less than or equal to n and greater than 0
 * @return an array of the batch means
 */
@Suppress("unused")
fun DoubleArray.batchMeans(numBatches: Int): DoubleArray {
    return BatchStatistic.batchMeans(this, numBatches)
}

/**
 * Returns a BoxPlotSummary that summarizes the array of values
 *
 * @return a BoxPlotSummary summarizing the data
 */
@Suppress("unused")
fun DoubleArray.boxPlotSummary(): BoxPlotSummary {
    return KSLArrays.boxPlotSummary(this)
}

/**
 *  Inserts the value at the index, returning a new array
 */
@Suppress("unused")
fun DoubleArray.insertAt(value: Double, index: Int): DoubleArray {
    return KSLArrays.insertAt(this, value, index)
}

/**
 *  Inserts the value at the index, returning a new array
 */
@Suppress("unused")
fun IntArray.insertAt(value: Int, index: Int): IntArray {
    return KSLArrays.insertAt(this, value, index)
}

/**
 *  Remove the element at the index, returning a new array
 */
@Suppress("unused")
fun DoubleArray.removeAt(index: Int): DoubleArray {
    return KSLArrays.removeAt(this, index)
}

/**
 *  Remove the element at the index, returning a new array
 */
@Suppress("unused")
fun IntArray.removeAt(index: Int): IntArray {
    return KSLArrays.removeAt(this, index)
}

/**
 * Returns a histogram that summarizes the array of values
 *
 * @return a Histogram summarizing the data
 */
@Suppress("unused")
fun DoubleArray.histogram(breakPoints: DoubleArray = Histogram.recommendBreakPoints(this)): Histogram {
    return Histogram.create(this, breakPoints)
}

/**
 * @return a copy of the sorted array in ascending order representing the order statistics
 */
@Suppress("unused")
fun DoubleArray.orderStatistics(): DoubleArray {
    return KSLArrays.orderStatistics(this)
}

/**
 *  Returns a new array with duplicate data values removed from the original array,
 *  preserving the order of the observations.
 */
@Suppress("unused")
fun DoubleArray.removeDuplicates(): DoubleArray {
    return KSLArrays.removeDuplicates(this)
}

/**
 *  Returns a new array in the same order as the original array but
 *  with the specified value removed.  All instances of the value
 *  will be removed.
 */
@Suppress("unused")
fun DoubleArray.removeValue(value: Double): DoubleArray {
    val values = mutableListOf<Double>()
    for (x in this) {
        if (x != value) {
            values.add(x)
        }
    }
    return values.toDoubleArray()
}

/**
 * @param x    the ordinate to check
 * @return the number of data points greater than x
 */
@Suppress("unused")
fun DoubleArray.countGreaterThan(x: Double): Int {
    return KSLArrays.countGreaterThan(this, x)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points greater than or equal to x
 */
@Suppress("unused")
fun DoubleArray.countGreaterEqualTo(x: Double): Int {
    return KSLArrays.countGreaterEqualTo(this, x)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points less than x
 */
@Suppress("unused")
fun DoubleArray.countLessThan(x: Double): Int {
    return KSLArrays.countLessThan(this, x)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points less than or equal to x
 */
@Suppress("unused")
fun DoubleArray.countLessEqualTo(x: Double): Int {
    return KSLArrays.countLessEqualTo(this, x)
}

/**
 * @param x the ordinate to check
 * @return the proportion of the data points that are less than or equal to x
 */
@Suppress("unused")
fun DoubleArray.empiricalCDF(x: Double): Double {
    return Statistic.empiricalCDF(this, x)
}

/**
 * Returns the index associated with the minimum element in the array For
 * ties. This returns the first found.
 *
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun DoubleArray.indexOfMin(): Int {
    return KSLArrays.indexOfMin(this)
}

/**
 * @return the minimum value in the array
 */
@Suppress("unused")
fun DoubleArray.min(): Double {
    return KSLArrays.min(this)
}

/**
 * Returns the index associated with the maximum element in the array For
 * ties. This returns the first found.
 *
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun DoubleArray.indexOfMax(): Int {
    return KSLArrays.indexOfMax(this)
}

/**
 * @return the maximum value in the array
 */
@Suppress("unused")
fun DoubleArray.max(): Double {
    return KSLArrays.max(this)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points greater than x
 */
@Suppress("unused")
fun IntArray.countGreaterThan(x: Int): Int {
    return KSLArrays.countGreaterThan(this, x)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points greater than or equal to x
 */
@Suppress("unused")
fun IntArray.countGreaterEqualTo(x: Int): Int {
    return KSLArrays.countGreaterEqualTo(this, x)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points less than x
 */
@Suppress("unused")
fun IntArray.countLessThan(x: Int): Int {
    return KSLArrays.countLessThan(this, x)
}

/**
 * @param x    the ordinate to check
 * @return the number of data points less than or equal to x
 */
@Suppress("unused")
fun IntArray.countLessEqualTo(x: Int): Int {
    return KSLArrays.countLessEqualTo(this, x)
}

/**
 * Returns the index associated with the minimum element in the array For
 * ties. This returns the first found.
 *
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun IntArray.indexOfMin(): Int {
    return KSLArrays.indexOfMin(this)
}

/**
 * @return the minimum value in the array
 */
@Suppress("unused")
fun IntArray.min(): Int {
    return KSLArrays.min(this)
}

/**
 * Returns the index associated with the maximum element in the array For
 * ties. This returns the first found.
 *
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun IntArray.indexOfMax(): Int {
    return KSLArrays.indexOfMax(this)
}

/**
 * @return the maximum value in the array
 */
@Suppress("unused")
fun IntArray.max(): Int {
    return KSLArrays.max(this)
}

/**
 * Returns the index associated with the minimum element in the array For
 * ties. This returns the first found.
 *
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun LongArray.indexOfMin(): Int {
    return KSLArrays.indexOfMin(this)
}

/**
 * @return the minimum value in the array
 */
@Suppress("unused")
fun LongArray.min(): Long {
    return KSLArrays.min(this)
}

/**
 * Returns the index associated with the maximum element in the array For
 * ties. This returns the first found.
 *
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun LongArray.indexOfMax(): Int {
    return KSLArrays.indexOfMax(this)
}

/**
 * @return the maximum value in the array
 */
@Suppress("unused")
fun LongArray.max(): Long {
    return KSLArrays.max(this)
}

/**
 * @return max() - min()
 */
@Suppress("unused")
fun DoubleArray.range(): Double {
    return KSLArrays.range(this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence for the element
 */
@Suppress("unused")
fun DoubleArray.findIndex(element: Double): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return true if an instance of the element is found
 */
@Suppress("unused")
fun DoubleArray.hasElement(element: Double): Boolean {
    return KSLArrays.hasElement(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence for the element
 */
@Suppress("unused")
fun IntArray.findIndex(element: Int): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return true if an instance of the element is found
 */
@Suppress("unused")
fun IntArray.hasElement(element: Int): Boolean {
    return KSLArrays.hasElement(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence for the element
 */
@Suppress("unused")
fun LongArray.findIndex(element: Long): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return true if an instance of the element is found
 */
@Suppress("unused")
fun LongArray.hasElement(element: Long): Boolean {
    return KSLArrays.hasElement(element, this)
}

/**
 *
 * @return true if the array has at least one 0.0
 */
@Suppress("unused")
fun DoubleArray.hasZero(): Boolean {
    return KSLArrays.hasZero(this)
}

/**
 *
 * @return true if the array has at least one 0.0
 */
@Suppress("unused")
fun IntArray.hasZero(): Boolean {
    return KSLArrays.hasZero(this)
}

/**
 *
 * @return true if the array has at least one 0.0
 */
@Suppress("unused")
fun LongArray.hasZero(): Boolean {
    return KSLArrays.hasZero(this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence for the element
 */
@Suppress("unused")
fun Array<String>.findIndex(element: String): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * Returns a new array that has been scaled so that the values are between
 * the minimum and maximum values of the supplied array
 *
 * @return the scaled array
 */
@Suppress("unused")
fun DoubleArray.minMaxScaledArray(): DoubleArray {
    return KSLArrays.minMaxScaledArray(this)
}

/**
 * Returns a new array that has been scaled so that the values are
 * the (x - avg)/sd values of the supplied array
 *
 *
 * @return the scaled array
 */
@Suppress("unused")
fun DoubleArray.normScaledArray(): DoubleArray {
    return KSLArrays.normScaledArray(this)
}

/**
 * Copies all but element index of the array fromA into the array toB
 * If fromA has 1 element, toB will be empty
 * @param index index of the element to leave out, must be 0 to fromA.length-1
 * @return a reference to the array toB
 */
@Suppress("unused")
fun DoubleArray.copyWithout(index: Int): DoubleArray {
    return KSLArrays.copyWithout(index, this)
}

/**
 *  Computes the product of the elements in the array
 */
@Suppress("unused")
fun DoubleArray.product(): Double {
    var p = 1.0
    for (x in this) {
        p = p * x
    }
    return p
}

/**
 * @param c the constant to add to each element
 * @return the transformed array
 */
@Suppress("unused")
fun DoubleArray.addConstant(c: Double): DoubleArray {
    return KSLArrays.addConstant(this, c)
}

/**
 * @param c the constant to subtract from each element
 * @return the transformed array
 */
@Suppress("unused")
fun DoubleArray.subtractConstant(c: Double): DoubleArray {
    return KSLArrays.subtractConstant(this, c)
}

/**
 * @param c the constant to multiply against each element
 * @return the transformed array
 */
@Suppress("unused")
fun DoubleArray.multiplyConstant(c: Double): DoubleArray {
    return KSLArrays.multiplyConstant(this, c)
}

/**
 * @param c the constant to divide each element
 * @return the transformed array
 */
@Suppress("unused")
fun DoubleArray.divideConstant(c: Double): DoubleArray {
    return KSLArrays.divideConstant(this, c)
}

/**
 * Multiplies the two arrays element by element. Arrays must have same length.
 *
 * @param b the second array
 * @return the array containing a[i]*b[i]
 */
@Suppress("unused")
fun DoubleArray.multiplyElements(b: DoubleArray): DoubleArray {
    return KSLArrays.multiplyElements(this, b)
}

/**
 * Assumes that the 2-D array can be ragged. Returns the number of columns
 * necessary that would cause the array to not be ragged. In other words,
 * the minimum number of columns to make the array an un-ragged array (matrix) where
 * all row arrays have the same number of elements.
 *
 * @return the minimum number of columns in the arrays
 */
@Suppress("unused")
fun Array<DoubleArray>.minNumColumns(): Int {
    return KSLArrays.minNumColumns(this)
}

/**
 * Copies the array by trimming to the minimum number of columns of the
 * supplied (potentially ragged) array so that the returned array is rectangular,
 * where all row arrays have the same number of elements (columns)
 *
 * @return the copy
 */
@Suppress("unused")
fun Array<DoubleArray>.trimToRectangular(): Array<DoubleArray> {
    return KSLArrays.trimToRectangular(this)
}

/**  Converts the 2-D array to a 1-D array by processing
 *   the source [src] array row-wise and concatenating the rows.
 *   For example, if the data is organized as follows:
 *
 *   1  2   3
 *   4  5   6
 *   7  8   9
 *
 *   Then the resulting array will be (1,2,3,4,5,6,7,8,9).
 *   In general, the source array may be ragged.
 *
 */
@Suppress("unused")
fun Array<DoubleArray>.concatenateTo1DArray(): DoubleArray {
    return KSLArrays.concatenateTo1DArray(this)
}

/**
 * Copies the 2-D array by expanding to the maximum number of columns of the
 * supplied (ragged) array so that the returned array is rectangular,
 * where all row arrays have the same number of elements (columns).
 *
 *
 * The expanded elements will be filled with the supplied fill value
 *
 * @param fillValue the value to fill if needed, default is 0.0
 * @return the copy
 */
@Suppress("unused")
fun Array<DoubleArray>.expandToRectangular(fillValue: Double = 0.0): Array<DoubleArray> {
    return KSLArrays.expandToRectangular(this, fillValue)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
@Suppress("unused")
fun <T> Array<Array<T>>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
@Suppress("unused")
fun Array<DoubleArray>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
@Suppress("unused")
fun Array<IntArray>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
@Suppress("unused")
fun Array<LongArray>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * Assumes that the array can be ragged. Returns the number of elements in
 * the row array that has the most elements.
 *
 * @return the maximum number of columns in the array
 */
@Suppress("unused")
fun Array<DoubleArray>.maxNumColumns(): Int {
    return KSLArrays.minNumColumns(this)
}

/**
 * @param k      the kth column to be extracted (zero-based indexing)
 * @return a copy of the extracted column
 */
@Suppress("unused")
fun Array<DoubleArray>.column(k: Int): DoubleArray {
    return KSLArrays.column(k, this)
}

/**
 * @param k      the kth column to be extracted (zero-based indexing)
 * @return a copy of the extracted column
 */
@Suppress("unused")
fun Array<IntArray>.column(k: Int): IntArray {
    return KSLArrays.column(k, this)
}

/**
 * @param k      the kth column to be extracted (zero-based indexing)
 * @return a copy of the extracted column
 */
@Suppress("unused")
fun Array<LongArray>.column(k: Int): LongArray {
    return KSLArrays.column(k, this)
}

/**
 * @param k      the kth column to be extracted (zero-based indexing)
 * @return a copy of the extracted column
 */
@Suppress("unused")
inline fun <reified T> Array<Array<T>>.column(k: Int): Array<T> {
    return KSLArrays.column(k, this)
}

/**
 * @return a copy of the array
 */
@Suppress("unused")
fun Array<DoubleArray>.copyOf(): Array<DoubleArray> {
    return KSLArrays.copy2DArray(this)
}

/**
 * @return a copy of the array
 */
@Suppress("unused")
fun Array<IntArray>.copyOf(): Array<IntArray> {
    return KSLArrays.copy2DArray(this)
}

/**
 * @return a copy of the array
 */
@Suppress("unused")
fun Array<LongArray>.copyOf(): Array<LongArray> {
    return KSLArrays.copy2DArray(this)
}

/**
 *  Fills the array with the value
 */
@Suppress("unused")
fun DoubleArray.fill(theValue: GetValueIfc = ConstantRV.ZERO) {
    KSLArrays.fill(this, theValue)
}

/**
 *  Fills the array with the provided value
 */
@Suppress("unused")
fun Array<DoubleArray>.fill(theValue: GetValueIfc = ConstantRV.ZERO) {
    KSLArrays.fill(this, theValue)
}

/**
 * The destination array is mutated by this method
 *
 * @param col  the column in the destination to fill
 * @param src  the source for filling the column
 */
@Suppress("unused")
fun Array<DoubleArray>.fillColumn(col: Int, src: DoubleArray) {
    KSLArrays.fillColumn(col, src, this)
}

/**
 * The array must not be null
 *
 * @return the sum of the squares for the elements of the array
 */
@Suppress("unused")
fun DoubleArray.sumOfSquares(): Double {
    return KSLArrays.sumOfSquares(this)
}

/**
 * The array must not be null
 *
 * @return the sum of the square roots for the elements of the array
 */
@Suppress("unused")
fun DoubleArray.sumOfSquareRoots(): Double {
    return KSLArrays.sumOfSquareRoots(this)
}

/**
 * Adds the two arrays element by element. Arrays must have the same length and must not be null.
 *
 * @param b the second array
 * @return the array containing a[i]+b[i]
 */
@Suppress("unused")
fun DoubleArray.addElements(b: DoubleArray): DoubleArray {
    return KSLArrays.addElements(this, b)
}

/**
 * @param another the second array
 * @return true if all elements are equal
 */
@Suppress("unused")
fun DoubleArray.compareTo(another: DoubleArray): Boolean {
    return KSLArrays.compareArrays(this, another)
}

/**
 * Converts any null values to replaceNull. For Array<Double> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
@Suppress("unused")
fun Array<Double?>.toPrimitives(replaceNull: Double = 0.0): DoubleArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For List<Double> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
@Suppress("unused")
fun List<Double?>.toPrimitives(replaceNull: Double = 0.0): DoubleArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For Array<Int> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
@Suppress("unused")
fun Array<Int?>.toPrimitives(replaceNull: Int = 0): IntArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For List<Int> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
@Suppress("unused")
fun List<Int?>.toPrimitives(replaceNull: Int = 0): IntArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For Array<Long> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
@Suppress("unused")
fun Array<Long?>.toPrimitives(replaceNull: Long = 0): LongArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For List<Long> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
@Suppress("unused")
fun List<Long?>.toPrimitives(replaceNull: Long = 0): LongArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Convert the array of double to an array of strings with each element the
 * corresponding value
 *
 * @return the array of strings representing the values of the doubles
 */
@Suppress("unused")
fun DoubleArray.toStrings(): Array<String> {
    return KSLArrays.toStrings(this)
}

/**
 * @return a comma-delimited string of the array, if empty, returns the empty string
 */
@Suppress("unused")
fun DoubleArray.toCSVString(): String {
    return KSLArrays.toCSVString(this)
}

/**
 * @return a comma-delimited string of the array, if empty, returns the empty string
 */
@Suppress("unused")
fun IntArray.toCSVString(): String {
    return KSLArrays.toCSVString(this)
}

/**
 * @return a comma-delimited string of the array, if empty, returns the empty string
 */
@Suppress("unused")
fun LongArray.toCSVString(): String {
    return KSLArrays.toCSVString(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
@Suppress("unused")
fun IntArray.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
@Suppress("unused")
fun Array<Int>.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
@Suppress("unused")
fun LongArray.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
@Suppress("unused")
fun Array<Long>.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the 2D array of double to a 2D array of Double with each element the
 * corresponding value
 *
 * @return the array of Doubles representing the values of the doubles
 */
@Suppress("unused")
fun Array<DoubleArray>.toDoubles(): Array<Array<Double>> {
    return KSLArrays.toDoubles(this)
}

/**
 *  Converts the 2D array of doubles to a map that holds the arrays
 *  by column. If the column name is not supplied, then the column is called col1, col2, etc. The
 *  2D array must be rectangular.
 *  @param colNames the names of the columns (optional)
 */
@Suppress("unused")
fun Array<DoubleArray>.toMapOfColumns(colNames: List<String> = emptyList()): Map<String, DoubleArray> {
    val nCol = KSLArrays.numColumns(this)
    val names = (1..nCol).map { "col$it" }.toList()
    val map = mutableMapOf<String, DoubleArray>()
    for ((i, name) in names.withIndex()) {
        // use the supplied names but if it doesn't exist, use the made up name
        map[colNames.getOrElse(i) { name }] = this.column(i)
    }
    return map
}

/**
 *  Converts the 2D array of doubles to a map that holds the arrays
 *  by column. If the column name is not supplied, then the column is called col1, col2, etc. The
 *  2D array must be rectangular.
 *  @param colNames the names of the columns (optional)
 */
@Suppress("unused")
fun Array<DoubleArray>.toMapOfLists(colNames: List<String> = emptyList()): Map<String, List<Double>> {
    val nCol = KSLArrays.numColumns(this)
    val names = (1..nCol).map { "col$it" }.toList()
    val map = mutableMapOf<String, List<Double>>()
    for ((i, name) in names.withIndex()) {
        // use the supplied names but if it doesn't exist, use the made up name
        map[colNames.getOrElse(i) { name }] = this.column(i).toList()
    }
    return map
}

/**
 *  Converts the 2D array of doubles to a map that holds the arrays
 *  by rows. If the row name is not supplied, then the row is called row1, row2, etc. The
 *  2D array must be rectangular.
 *  @param rowNames the names of the rows (optional)
 */
@Suppress("unused")
fun Array<DoubleArray>.toMapOfRows(rowNames: List<String> = emptyList()): Map<String, DoubleArray> {
    val nRows = this.size
    val names = (1..nRows).map { "row$it" }.toList()
    val map = mutableMapOf<String, DoubleArray>()
    for ((i, name) in names.withIndex()) {
        // use the supplied names but if it doesn't exist, use the made up name
        map[rowNames.getOrElse(i) { name }] = this[i].copyOf()
    }
    return map
}

/**
 *  Computes the statistics for the 2D array of doubles by rows.
 *  If the row name is not supplied, then the row is called rowj where j is the
 *  number of the missing row name.
 *  @param rowNames the names of the rows (optional)
 */
@Suppress("unused")
fun Array<DoubleArray>.statisticsByRow(rowNames: List<String> = emptyList()): List<StatisticIfc> {
    val nRows = this.size
    val names = (1..nRows).map { "row$it" }.toList()
    val list = mutableListOf<StatisticIfc>()
    for ((i, name) in names.withIndex()) {
        // use the supplied names but if it doesn't exist, use the made up name
        val statName = rowNames.getOrElse(i) { name }
        list.add(Statistic(statName, this[i]))
    }
    return list
}

/**
 * Convert the 2D array of Int to a 2D array of Int with each element the
 * corresponding value
 *
 * @return the array of Int representing the values of the doubles
 */
@Suppress("unused")
fun Array<IntArray>.toInts(): Array<Array<Int>> {
    return KSLArrays.toInts(this)
}

/**
 * Convert the 2D array of Long to a 2D array of Long with each element the
 * corresponding value
 *
 * @return the array of Long representing the values of the doubles
 */
@Suppress("unused")
fun Array<LongArray>.toLongs(): Array<Array<Long>> {
    return KSLArrays.toLongs(this)
}

/**
 * Converts the array of strings to Doubles
 *
 * @param parseFail the value to use if the parse fails or string is null by default Double.NaN
 * @return the parsed doubles as an array
 */
@Suppress("unused")
fun Array<String>.parseToDoubles(parseFail: Double = Double.NaN): DoubleArray {
    return KSLArrays.parseToDoubles(this, parseFail)
}

/**
 * Converts the list of strings to Doubles
 *
 * @param parseFail the value to use if the parse fails or string is null by default Double.NaN
 * @return the parsed doubles as an array
 */
@Suppress("unused")
fun List<String>.parseToDoubles(parseFail: Double = Double.NaN): DoubleArray {
    return KSLArrays.parseToDoubles(this, parseFail)
}

/**
 * Transposes the n rows by m columns array returned transpose[x][y] = array[y][x]
 *
 * @return an array with n columns and m rows
 */
@Suppress("unused")
fun Array<IntArray>.transpose(): Array<IntArray> {
    return KSLArrays.transpose(this)
}

/**
 * Transposes the n rows by m columns array returned transpose[x][y] = array[y][x]
 *
 * @return an array with n columns and m rows
 */
@Suppress("unused")
fun Array<DoubleArray>.transpose(): Array<DoubleArray> {
    return KSLArrays.transpose(this)
}

/**
 * Transposes the n rows by m columns array returned transpose[x][y] = array[y][x]
 *
 * @return an array with n columns and m rows
 */
@Suppress("unused")
fun Array<LongArray>.transpose(): Array<LongArray> {
    return KSLArrays.transpose(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun DoubleArray.isStrictlyIncreasing(): Boolean {
    return KSLArrays.isStrictlyIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly decreasing a_0 gt a_1 gt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun DoubleArray.isStrictlyDecreasing(): Boolean {
    return KSLArrays.isStrictlyDecreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are increasing a_0 lte a_1 lte a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun DoubleArray.isIncreasing(): Boolean {
    return KSLArrays.isIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are decreasing a_0 gte a_1 gte a_2, etc.
 *
 * @return true if all elements are decreasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun DoubleArray.isDecreasing(): Boolean {
    return KSLArrays.isDecreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are equal a_0 = a_1 = a_2, etc.
 *
 * @return true if all elements are equal, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun DoubleArray.isAllEqual(): Boolean {
    return KSLArrays.isAllEqual(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are different (distinct) a_0 != a_1 != a_2, etc.
 *
 * @return true if all elements are distinct, if there
 * are 0 elements, then it returns false
 */
@Suppress("unused")
fun DoubleArray.isAllDifferent(): Boolean {
    return KSLArrays.isAllDifferent(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are different (distinct) a_0 != a_1 != a_2, etc.
 *
 * @return true if all elements are distinct, if there
 * are 0 elements, then it returns false
 */
@Suppress("unused")
fun IntArray.isAllDifferent(): Boolean {
    return KSLArrays.isAllDifferent(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are equal a_0 = a_1 = a_2, etc.
 *
 * @return true if all elements are equal, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun IntArray.isAllEqual(): Boolean {
    return KSLArrays.isAllEqual(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun IntArray.isStrictlyIncreasing(): Boolean {
    return KSLArrays.isStrictlyIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly decreasing a_0 gt a_1 gt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun IntArray.isStrictlyDecreasing(): Boolean {
    return KSLArrays.isStrictlyDecreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are increasing a_0 lte a_1 lte a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun IntArray.isIncreasing(): Boolean {
    return KSLArrays.isIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are decreasing a_0 gte a_1 gte a_2, etc.
 *
 * @return true if all elements are decreasing, if there
 * are 0 elements, then it returns false, 1 element returns true
 */
@Suppress("unused")
fun IntArray.isDecreasing(): Boolean {
    return KSLArrays.isDecreasing(this)
}

/**
 *  Applies the transformation to each element of the array
 *  in place
 */
@Suppress("unused")
fun <T> Array<T>.mapInPlace(transform: (T) -> T) {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
}

/**
 *  Applies the transformation to each element of the array
 *  in place
 */
@Suppress("unused")
fun IntArray.mapInPlace(transform: (Int) -> Int) {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
}

/**
 *  Applies the transformation to each element of the array
 *  in place
 */
@Suppress("unused")
fun DoubleArray.mapInPlace(transform: (Double) -> Double) {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
}

/**
 * Returns the index associated with the minimum element in the list For
 * ties. This returns the first found.
 *
 * The list must not be null or empty
 * @return the index associated with the minimum element
 */
@Suppress("unused")
fun List<Double>.indexOfMin(): Int {
    require(isNotEmpty()) { "The list was empty" }
    var index = 0
    var min = Double.MAX_VALUE
    for (i in this.indices) {
        if (this[i] < min) {
            min = this[i]
            index = i
        }
    }
    return index
}

/**
 *  A simple implementation of linspace() found in python
 *  Returns evenly spaced values within a given interval start, stop
 *  @param start the starting value. Must be less than stop
 *  @param stop the stopping value. Must be greater than start
 *  @param num the number of points in the interval. Defaults to 50
 *  @param endpoint if true, the end point (stop) is included in the interval. Defaults to true.
 *  @return a list of the values
 */
@Suppress("unused")
fun linspace(start: Int, stop: Int, num: Int = 50, endpoint: Boolean = true): List<Double> {
    return linspace(start.toDouble(), stop.toDouble(), num, endpoint)
}

/**
 *  A simple implementation of linspace() found in python
 *  Returns evenly spaced values within a given interval start, stop
 *  @param range the range for the interval
 *  @param num the number of points in the interval. Defaults to 50
 *  @param endpoint if true the end point (stop) is included in the interval. Defaults to true.
 *  @return a list of the values
 */
@Suppress("unused")
fun linspace(range: IntRange, num: Int = 50, endpoint: Boolean = true): List<Double> {
    return linspace(range.first, range.last, num, endpoint)
}

/**
 *  A simple implementation of linspace() found in python
 *  Returns evenly spaced values within a given interval start, stop
 *  @param start the starting value. Must be less than stop
 *  @param stop the stopping value. Must be greater than start
 *  @param num the number of points in the interval. Defaults to 50
 *  @param endpoint if true, the end point (stop) is included in the interval. Defaults to true.
 *  @return a list of the values
 */
@Suppress("unused")
fun linspace(start: Double, stop: Double, num: Int = 50, endpoint: Boolean = true): List<Double> {
    require(start.isFinite()) { "start must be finite" }
    require(stop.isFinite()) { "stop must be finite" }
    require(!start.isNaN()) { "start must be not be NaN" }
    require(!stop.isNaN()) { "stop must be not be NaN" }
    require(start < stop) { "The starting value ($start) must be less that the stop value ($stop)." }
    val n = num.coerceAtLeast(1)
    if (n == 1) {
        return listOf(start)
    }
    val list = mutableListOf<Double>()
    val step = if (endpoint) {
        (stop - start) / (n - 1)
    } else {
        (stop - start) / n
    }
    for (i in 0 until n) {
        list.add(start + step * i)
    }
    return list
}

/**
 *  Computes the Manhattan distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun List<Int>.manhattanDistance(b: List<Int>): Int {
    require(size == b.size) { "Vectors must be of the same length" }
    var sum = 0
    for (i in indices) {
        sum = sum + abs(this[i] - b[i])
    }
    return sum
}

/**
 *  Computes the Manhattan distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun IntArray.manhattanDistance(b: IntArray): Int {
    require(size == b.size) { "Vectors must be of the same length" }
    var sum = 0
    for (i in indices) {
        sum = sum + abs(this[i] - b[i])
    }
    return sum
}

/**
 *  Computes the Manhattan distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun List<Double>.manhattanDistance(b: List<Double>): Double {
    require(size == b.size) { "Vectors must be of the same length" }
    var sum = 0.0
    for (i in indices) {
        sum = sum + abs(this[i] - b[i])
    }
    return sum
}

/**
 *  Computes the Manhattan distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun DoubleArray.manhattanDistance(b: DoubleArray): Double {
    require(size == b.size) { "Vectors must be of the same length" }
    var sum = 0.0
    for (i in indices) {
        sum = sum + abs(this[i] - b[i])
    }
    return sum
}

/**
 *  Computes the Chebyshev distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun IntArray.chebyshevDistance(b: IntArray): Int {
    require(size == b.size) { "Vectors must be of the same length" }
    var maxDiff = 0
    for (i in indices) {
        val diff = abs(this[i] - b[i])
        if (diff > maxDiff) {
            maxDiff = diff
        }
    }
    return maxDiff
}

/**
 *  Computes the Chebyshev distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun List<Int>.chebyshevDistance(b: List<Int>): Int {
    require(size == b.size) { "Vectors must be of the same length" }
    var maxDiff = 0
    for (i in indices) {
        val diff = abs(this[i] - b[i])
        if (diff > maxDiff) {
            maxDiff = diff
        }
    }
    return maxDiff
}

/**
 *  Computes the Chebyshev distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun DoubleArray.chebyshevDistance(b: DoubleArray): Double {
    require(size == b.size) { "Vectors must be of the same length" }
    var maxDiff = 0.0
    for (i in indices) {
        val diff = abs(this[i] - b[i])
        if (diff > maxDiff) {
            maxDiff = diff
        }
    }
    return maxDiff
}

/**
 *  Computes the Chebyshev distance between the two vectors.
 *  The vectors must be of the same size.
 */
@Suppress("unused")
fun List<Double>.chebyshevDistance(b: List<Double>): Double {
    require(size == b.size) { "Vectors must be of the same length" }
    var maxDiff = 0.0
    for (i in indices) {
        val diff = abs(this[i] - b[i])
        if (diff > maxDiff) {
            maxDiff = diff
        }
    }
    return maxDiff
}

/**
 *  Returns a list holding the indices of the sorted items in the array
 */
@Suppress("unused")
fun <T : Comparable<T>> Array<T>.sortedIndices(): List<Int> {
    // Pair each element with its original index
    val indexedArray = this.mapIndexed { index, value -> Pair(value, index) }

    // Sort the pairs based on the element's value
    val sortedIndexedArray = indexedArray.sortedBy { it.first }

    // Extract the original indices from the sorted pairs
    return sortedIndexedArray.map { it.second }
}