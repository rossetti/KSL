package ksl.utilities

import ksl.utilities.math.FunctionIfc
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.statistic.DoubleArraySaver
import ksl.utilities.statistic.Statistic
import kotlin.math.sqrt

/**
 * This class has some array manipulation methods that I have found useful over the years.
 */
object KSLArrays {
    /**
     * Returns the index associated with the minimum element in the array For
     * ties, this returns the first found.
     *
     * @param x the array to search, must not be null or empty
     * @return the index associated with the minimum element
     */
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
     * @param x the array to search, must not be null or empty
     * @return the minimum value in the array
     */
    fun min(x: DoubleArray): Double {
        return x[indexOfMin(x)]
    }

    /**
     * Returns the index associated with the maximum element in the array For
     * ties, this returns the first found.
     *
     * @param x the array to search, must not be null or empty
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
     * @param x the array to search, must not be null or empty
     * @return the maximum value in the array
     */
    fun max(x: DoubleArray): Double {
        return x[indexOfMax(x)]
    }

    /**
     * Returns the index associated with the minimum element in the array For
     * ties, this returns the first found.
     *
     * @param x the array to search, must not be null or empty
     * @return the index associated with the minimum element
     */
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
     * @param x the array to search, must not be null or empty
     * @return the minimum value in the array
     */
    fun min(x: IntArray): Int {
        return x[indexOfMin(x)]
    }

    /**
     * Returns the index associated with the maximum element in the array For
     * ties, this returns the first found
     *
     * @param x the array to search, must not be null or empty
     * @return the index associated with the maximum element
     */
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
     * @param x the array to search, must not be null or empty
     * @return the maximum value in the array
     */
    fun max(x: IntArray): Int {
        return x[indexOfMax(x)]
    }

    /**
     * Returns the index associated with the minimum element in the array For
     * ties, this returns the first found
     *
     * @param x the array to search, must not be null or empty
     * @return the index associated with the minimum element
     */
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
     * @param x the array to search, must not be null or empty
     * @return the minimum value in the array
     */
    fun min(x: LongArray): Long {
        return x[indexOfMin(x)]
    }

    /**
     * Returns the index associated with the maximum element in the array For
     * ties, this returns the first found
     *
     * @param x the array to search, must not be null or empty
     * @return the index associated with the maximum element
     */
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
     * @param x the array to search, must not be null or empty
     * @return the maximum value in the array
     */
    fun max(x: LongArray): Long {
        return x[indexOfMax(x)]
    }

    /**
     * @param array the array to operate on
     * @return max() - min()
     */
    fun range(array: DoubleArray): Double {
        val max = max(array)
        val min = min(array)
        return max - min
    }

    /**
     * If the array is empty, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence of the element
     */
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
     * @return true if the array as at least one zero element
     */
    fun hasZero(array: IntArray): Boolean {
        return findIndex(0, array) >= 0
    }

    /**
     *
     * @param array the array to check
     * @return true if the array as at least one zero element
     */
    fun hasZero(array: DoubleArray): Boolean {
        return findIndex(0.0, array) >= 0
    }

    /**
     *
     * @param array the array to check
     * @return true if the array as at least one zero element
     */
    fun hasZero(array: LongArray): Boolean {
        return findIndex(0, array) >= 0
    }

    /**
     * If the array is empty, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence of the element
     */
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
    fun hasElement(element: Double, array: DoubleArray): Boolean{
        return findIndex(element, array) >= 0
    }

    /**
     * If the array is empty, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence of the element
     */
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
    fun hasElement(element: Long, array: LongArray): Boolean{
        return findIndex(element, array) >= 0
    }

    /**
     * @param element the element to check
     * @param array the array to check
     * @return true if the array as at least one occurrence of the element
     */
    fun hasElement(element: Int, array: IntArray): Boolean{
        return findIndex(element, array) >= 0
    }

    /**
     * If the array is empty, -1 is returned.
     *
     * @param element the element to search for
     * @param array   the array to search in
     * @return the index of the first occurrence of the element
     */
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
     * @param array the array to scale, must not be null
     * @return the scaled array
     */
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
     * @param array the array to scale, must not be null
     * @return the scaled array
     */
    fun normScaledArray(array: DoubleArray): DoubleArray {
        val s = Statistic(array)
        val avg = s.average
        val sd = s.standardDeviation
        require(sd != 0.0) { "The array cannot be scaled because std dev == 0.0" }
        val x = DoubleArray(array.size)
        for (i in array.indices) {
            x[i] = (array[i] - avg) / sd
        }
        return x
    }

    /**
     * Copies all but element index of array fromA into array toB
     * If fromA has 1 element, toB will be empty
     * @param index index of element to leave out, must be 0 to fromA.length-1
     * @param fromA array to copy from, must not be null
     * @param toB   array to copy to, must be length fromA.length - 1
     * @return a reference to the array toB
     */
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
     * @param a the first array, must not be null
     * @param b the second array, must not be null
     * @return the summed product of the two arrays
     */
    fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "The length of the arrays was not equal" }
        require(a.size != 0) { "The arrays were empty!" }
        var sum = 0.0
        for (i in a.indices) {
            sum = sum + a[i] * b[i]
        }
        return sum
    }

    /**
     * The arrays must be rectangular and n columns of first must
     * be same and n rows for second
     *
     * @param first  the first array, must not be null
     * @param second the second array, must not be null
     * @return true if arrays can be multiplied
     */
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
     * @param first  the first array, must not be null, must be rectangular
     * @param second the second array, must not be null, must be rectangular
     * @return true if arrays have the same elements
     */
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
     * array equal to the number of columns of the second array.
     *
     * @param first  the first array, must not be null
     * @param second the second array, must not be null
     * @return the product of the arrays
     */
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
     * @param array a 2-D rectangular array, must not be null
     * @return the number of rows in the array
     */
    fun numRows(array: Array<DoubleArray>): Int {
        require(isRectangular(array)) { "The array was not rectangular" }
        return array.size
    }

    /**
     * @param array a 2-D rectangular array, must not be null
     * @return the number of columns in the array
     */
    fun numColumns(array: Array<DoubleArray>): Int {
        require(isRectangular(array)) { "The array was not rectangular" }
        return array[0].size
    }

    /**
     * @param a the array to add the constant to
     * @param c the constant to add to each element
     * @return the transformed array
     */
    fun addConstant(a: DoubleArray, c: Double): DoubleArray {
        for (i in a.indices) {
            a[i] = a[i] + c
        }
        return a
    }

    /**
     * @param a the array to add the constant to
     * @param c the constant to subtract from each element
     * @return the transformed array
     */
    fun subtractConstant(a: DoubleArray, c: Double): DoubleArray {
        return addConstant(a, -c)
    }

    /**
     * @param a the array to multiply the constant by
     * @param c the constant to multiply against each element
     * @return the transformed array
     */
    fun multiplyConstant(a: DoubleArray, c: Double): DoubleArray {
        for (i in a.indices) {
            a[i] = a[i] * c
        }
        return a
    }

    /**
     * @param a the array to divide the constant by
     * @param c the constant to divide each element, cannot be zero
     * @return the transformed array
     */
    fun divideConstant(a: DoubleArray, c: Double): DoubleArray {
        require(c != 0.0) { "Cannot divide by zero" }
        return multiplyConstant(a, 1.0 / c)
    }

    /**
     * Multiplies the two arrays element by element. Arrays must have same length.
     *
     * @param a the first array
     * @param b the second array
     * @return the array containing a[i]*b[i]
     */
    fun multiplyElements(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "The array lengths must match" }
        val c = DoubleArray(a.size)
        for (i in a.indices) {
            c[i] = a[i] * b[i]
        }
        return c
    }

    /**
     * Divides the arrays element by element. Arrays must have same length and must not be null.
     *
     * @param a the first array
     * @param b the second array, must not have any zero elements
     * @return the array containing a[i]/b[i]
     */
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
     * @param array2D the array to check, must not be null
     * @return the minimum number of columns in the array
     */
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
     * Copies the supplied array by trimming to the minimum number of columns of the
     * supplied (potentially ragged) array so that the returned array is rectangular,
     * where all row arrays have the same number of elements (columns)
     *
     * @param array2D the array to copy
     * @return the copy
     */
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
     * Copies the supplied 2-D array by expanding to the maximum number of columns of the
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
     * An array is rectangular if all rows have the same number of elements (columns).
     *
     * @param array2D the array to check
     * @return true if the array is rectangular
     */
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
     * @param array the square array, must not be null
     * @return the diagonal elements of the array as an array
     */
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
     * @param array2D the array to check, must not be null
     * @return the minimum number of columns in the array
     */
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
     * @param k      the kth column to be extracted (zero based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e. all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    fun column(k: Int, matrix: Array<DoubleArray>): DoubleArray {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        val column = DoubleArray(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][k]
        }
        return column
    }

    /**
     * @param k      the kth column to be extracted (zero based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e. all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    fun column(k: Int, matrix: Array<IntArray>): IntArray {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        val column = IntArray(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][k]
        }
        return column
    }

    /**
     * @param k      the kth column to be extracted (zero based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e. all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    fun column(k: Int, matrix: Array<LongArray>): LongArray {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        val column = LongArray(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][k]
        }
        return column
    }

    /**
     * @param index  the column to be extracted (zero based indexing)
     * @param matrix must not be null, assumed 2D rectangular array (i.e. all rows have the same number of columns)
     * @return a copy of the extracted column
     */
    fun column(index: Int, matrix: Array<Array<Any>>): Array<Any?> {
        require(isRectangular(matrix)) { "The matrix was not rectangular" }
        //TODO can this be made generic
        val column = arrayOfNulls<Any>(matrix.size) // Here I assume a rectangular 2D array!
        for (i in column.indices) {
            column[i] = matrix[i][index]
        }
        return column
    }

    /**
     * @param src the source array to copy
     * @return a copy of the array
     */
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
    fun copy2DArray(src: Array<LongArray>): Array<LongArray> {
        if (src.isEmpty()) {
            return Array(0) { LongArray(0) }
        }
        return Array(src.size) { src[it].copyOf() }
    }

    /**
     *
     * @param array the array to fill, must not be null
     * @param theValue the supplier of the value, must not be null
     */
    fun fill(array: DoubleArray, theValue: GetValueIfc = ConstantRV.ZERO) {
        for (i in array.indices) {
            array[i] = theValue.value()
        }
    }

    /**
     *
     * @param array the array to fill, must not be null
     * @param theValue the supplier of the value, must not be null
     */
    fun fill(array: Array<DoubleArray>, theValue: GetValueIfc = ConstantRV.ZERO) {
        for (doubles in array) {
            fill(doubles, theValue)
        }
    }

    /**
     * The destination array is mutated by this method
     *
     * @param col  the column in the destination to fill
     * @param src  the source for filling the column, must not be null
     * @param dest the destination array, assumed to be rectangular, must not be null
     */
    fun fillColumn(col: Int, src: DoubleArray, dest: Array<DoubleArray>) {
        require(dest.size == src.size) { "The source array length and destination array must have the same number of rows" }
        require(isRectangular(dest)) { "The matrix was not rectangular" }
        for (i in src.indices) {
            dest[i][col] = src[i]
        }
    }

    /**
     * The array must not be null
     *
     * @param array the input array
     * @return the sum of the squares of the elements of the array
     */
    fun sumOfSquares(array: DoubleArray): Double {
        var sum = 0.0
        for (v in array) {
            sum = sum + v * v
        }
        return sum
    }

    /**
     * The array must have non-negative elements and not be null
     *
     * @param array the input array
     * @return the sum of the square roots of the elements of the array
     */
    fun sumOfSquareRoots(array: DoubleArray): Double {
        var sum = 0.0
        for (v in array) {
            sum = sum + sqrt(v)
        }
        return sum
    }

    /**
     * Adds the two arrays element by element. Arrays must have same length and must not be null.
     *
     * @param a the first array
     * @param b the second array
     * @return the array containing a[i]+b[i]
     */
    fun addElements(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "The array lengths must match" }
        val c = DoubleArray(a.size)
        for (i in a.indices) {
            c[i] = a[i] + b[i]
        }
        return c
    }

    /**
     * Adds the arrays element by element. Arrays must have same length and must not be null.
     *
     * @param a the first array
     * @param b the second array
     * @return the array containing a[i]-b[i]
     */
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
     * @param targetClass the class type to find in the list, should be same as
     * T
     * @return a list that holds the items of the targetClass
    </T> */
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
     * @param targetClass the class type to find in the list, should be same as
     * T
     * @return a list that holds the items of the targetClass
     */
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
    fun toPrimitives(list: List<Long?>, replaceNull: Long = 0): LongArray {
        if (list.isEmpty()) {
            return LongArray(0)
        }
        return LongArray(list.size) { list[it] ?: replaceNull }
    }

    /**
     * Convert the array of double to an array of strings with each element the
     * corresponding value
     *
     * @param array the array of doubles
     * @return the array of strings representing the values of the doubles
     */
    fun toStrings(array: DoubleArray): Array<String> {
        if (array.isEmpty()) {
            return emptyArray()
        }
        return Array(array.size) { array[it].toString() }
    }

    /**
     * @param array the array to convert
     * @return a comma delimited string of the array, if empty, returns the empty string
     */
    fun toCSVString(array: DoubleArray): String {
        if (array.isEmpty()) {
            return ""
        }
        return array.joinToString()
    }

    /**
     * @param array the array to convert
     * @return a comma delimited string of the array, if empty or null, returns the empty string
     */
    fun toCSVString(array: IntArray): String {
        if (array.isEmpty()) {
            return ""
        }
        return array.joinToString()
    }

    /**
     * @param array the array to convert
     * @return a comma delimited string of the array, if empty or null, returns the empty string
     */
    fun toCSVString(array: LongArray): String {
        if (array.isEmpty()) {
            return ""
        }
        return array.joinToString()
    }

//    /**
//     * Convert the array of double to an array of Double with each element the
//     * corresponding value
//     *
//     * @param array the array of doubles
//     * @return the array of Doubles representing the values of the doubles
//     */
//    fun toDoubles(array: DoubleArray): Array<Double> {
//        if (array.isEmpty()) {
//            return emptyArray()
//        }
//        return array.toTypedArray()
//    }

    /**
     * Convert the array of int to an array of double with each element the
     * corresponding value
     *
     * @param array the array of ints
     * @return the array of doubles representing the values of the ints
     */
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
    fun toDoubles(array: Array<DoubleArray>): Array<Array<Double>> {
        if (array.isEmpty()) {
            return Array(0) { emptyArray() }
        }
        return Array(array.size) { array[it].toTypedArray() }
    }

//    /**
//     * Convert the array of int to an array of Intger with each element the
//     * corresponding value
//     *
//     * @param array the array of ints
//     * @return the array of Integers representing the values of the ints
//     */
//    fun toInts(array: IntArray): Array<Int> {
//        if (array.isEmpty()) {
//            return emptyArray()
//        }
//        return array.toTypedArray()
//    }

    /**
     * Convert the 2D array of int to a 2D array of Integer with each element the
     * corresponding value
     *
     * @param array the array of int
     * @return the array of Integer representing the values of the int
     */
    fun toInts(array: Array<IntArray>): Array<Array<Int>> {
        if (array.isEmpty()) {
            return Array(0) { emptyArray() }
        }
        return Array(array.size) { array[it].toTypedArray() }
    }

    /**
     * Convert the 2D array of int to a 2D array of Long with each element the
     * corresponding value
     *
     * @param array the array of int
     * @return the array of Integer representing the values of the int
     */
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
     * @param parseFail the fail to use if the parse fails or string is null, by default Double.NaN
     * @return the parsed doubles as an array
     */
    fun parseToDoubles(dblStrings: Array<String?>, parseFail: Double = Double.NaN): DoubleArray {
        if (dblStrings.isEmpty()) {
            return DoubleArray(0)
        }
        val target = DoubleArray(dblStrings.size)
        for (i in dblStrings.indices) {
//            dblStrings[i]?.toDoubleOrNull()?: Double.NaN
            if (dblStrings[i] == null) {
                target[i] = parseFail
            } else {
                try {
                    target[i] = dblStrings[i]!!.toDouble()
                } catch (e: NumberFormatException) {
                    target[i] = parseFail
                }
            }
        }
        return target
    }

    /**
     * Converts the array of strings to Doubles
     *
     * @param dblStrings a list of strings that represent Doubles
     * @param parseFail the fail to use if the parse fails or string is null, by default Double.NaN
     * @return the parsed doubles as an array
     */
    fun parseToDoubles(dblStrings: List<String?>, parseFail: Double = Double.NaN): DoubleArray {
        return parseToDoubles(dblStrings.toTypedArray(), parseFail)
    }

    /**
     * Transposes the array returned transpose[x][y] = array[y][x]
     *
     * @param array an array with m rows and n columns
     * @return an array with n columns and m rows
     */
    fun transpose2DArray(array: Array<IntArray>): Array<IntArray> {
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
    fun transpose2DArray(array: Array<DoubleArray>): Array<DoubleArray> {
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
    fun transpose2DArray(array: Array<LongArray>): Array<LongArray> {
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
     * Each labeled array in the map becomes a row in the returned array, which may be ragged because
     * each row in the array may have a different length.
     *
     * @param labeledRows a map holding named rows of data
     * @return a 2D array, where rows of the array hold the data in the order returned
     * from the string labels.
     */
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
    fun copyToColumns(labeledColumns: LinkedHashMap<String, DoubleArray>): Array<DoubleArray> {
        if (labeledColumns.isEmpty()) {
            return Array(0) { DoubleArray(0) }
        }
        val data = copyToRows(labeledColumns)
        require(isRectangular(data)) { "The stored arrays do not have the same number of elements" }
        return transpose2DArray(data)
    }

    /**
     * Assumes that the entries in the list are string representations of double values.
     * Each String[] can have a different number of elements.  Thus, the returned
     * array may be ragged.
     *
     * @param entries the list of data entries
     * @return the 2D array
     */
    fun parseTo2DArray(entries: List<Array<String?>>): Array<DoubleArray?> {
        // read as 2-D array//TODO review this
        val data = arrayOfNulls<DoubleArray>(entries.size)
        val iterator = entries.iterator()
        var row = 0
        while (iterator.hasNext()) {
            val strings = iterator.next()
            val rowData = parseToDoubles(strings)
            data[row] = rowData
            row++
        }
        return data
    }

    /**
     * @param array the array of objects
     * @param <T>   the type of the objects
     * @return a String array holding the string value of the elements of the array
    </T> */
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
     * @param array the array to check, must not be null
     * @return true if all elements are strictly increasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are strictly increasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are increasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are decreasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are equal, if there
     * are 0 elements then it returns false, 1 element returns true
     */
    fun isAllEqual(array: DoubleArray): Boolean {
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
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 != a_1 != a_2, etc.
     *
     * @param array the array to check, must not be null
     * @return true if all elements are different, if there
     * are 0 elements then it returns false, 1 element returns true
     */
    fun isAllDifferent(array: DoubleArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] == array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 != a_1 != a_2, etc.
     *
     * @param array the array to check, must not be null
     * @return true if all elements are different, if there
     * are 0 elements then it returns false, 1 element returns true
     */
    fun isAllDifferent(array: IntArray): Boolean {
        if (array.isEmpty()) {
            return false
        }
        if (array.size == 1) {
            return true
        }
        for (i in 1 until array.size) {
            if (array[i - 1] == array[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are equal a_0 = a_1 = a_2, etc.
     *
     * @param array the array to check, must not be null
     * @return true if all elements are equal, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * Examines each element, a_i starting at 0, and determines if all
     * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
     *
     * @param array the array to check, must not be null
     * @return true if all elements are strictly increasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are strictly increasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are increasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
     * @param array the array to check, must not be null
     * @return true if all elements are decreasing, if there
     * are 0 elements then it returns false, 1 element returns true
     */
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
    fun abs(array: DoubleArray) {
        for (i in array.indices) {
            array[i] = kotlin.math.abs(array[i])
        }
    }

    /**
     * Element-wise application of the supplied function. The
     * array is changed in place. Using FunctionIfc avoids autoboxing
     * when dealing with primitive doubles.
     *
     * @param array    the array to apply the function on, must not be null
     * @param function the function to apply, must not be null
     */
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
     * @param array    the array to apply the function on, must not be null
     * @param function the function to apply, must not be null
     */
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
     * @param array the array to check, must not be null
     * @return true if any element of array is NaN
     */
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
    fun dataInInterval(array: DoubleArray, interval: Interval): DoubleArray {
        val saver = DoubleArraySaver()
        for (x in array) {
            if (interval.contains(x)) {
                saver.save(x)
            }
        }
        return saver.savedData()
    }

}

/** Extension functions and other functions for working with arrays
 * @author rossetti@uark.edu
 */

//TODO add extension functions
/**
 * Returns the index associated with the minimum element in the array For
 * ties, this returns the first found.
 *
 * @return the index associated with the minimum element
 */
fun DoubleArray.indexOfMin(): Int {
    return KSLArrays.indexOfMin(this)
}

/**
 * @return the minimum value in the array
 */
fun DoubleArray.min(): Double {
    return KSLArrays.min(this)
}

/**
 * Returns the index associated with the maximum element in the array For
 * ties, this returns the first found.
 *
 * @return the index associated with the minimum element
 */
fun DoubleArray.indexOfMax(): Int {
    return KSLArrays.indexOfMax(this)
}

/**
 * @return the maximum value in the array
 */
fun DoubleArray.max(): Double {
    return KSLArrays.max(this)
}

/**
 * Returns the index associated with the minimum element in the array For
 * ties, this returns the first found.
 *
 * @return the index associated with the minimum element
 */
fun IntArray.indexOfMin(): Int {
    return KSLArrays.indexOfMin(this)
}

/**
 * @return the minimum value in the array
 */
fun IntArray.min(): Int {
    return KSLArrays.min(this)
}

/**
 * Returns the index associated with the maximum element in the array For
 * ties, this returns the first found.
 *
 * @return the index associated with the minimum element
 */
fun IntArray.indexOfMax(): Int {
    return KSLArrays.indexOfMax(this)
}

/**
 * @return the maximum value in the array
 */
fun IntArray.max(): Int {
    return KSLArrays.max(this)
}

/**
 * Returns the index associated with the minimum element in the array For
 * ties, this returns the first found.
 *
 * @return the index associated with the minimum element
 */
fun LongArray.indexOfMin(): Int {
    return KSLArrays.indexOfMin(this)
}

/**
 * @return the minimum value in the array
 */
fun LongArray.min(): Long {
    return KSLArrays.min(this)
}

/**
 * Returns the index associated with the maximum element in the array For
 * ties, this returns the first found.
 *
 * @return the index associated with the minimum element
 */
fun LongArray.indexOfMax(): Int {
    return KSLArrays.indexOfMax(this)
}

/**
 * @return the maximum value in the array
 */
fun LongArray.max(): Long {
    return KSLArrays.max(this)
}

/**
 * @return max() - min()
 */
fun DoubleArray.range(): Double {
    return KSLArrays.range(this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence of the element
 */
fun DoubleArray.findIndex(element: Double): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return true if an instance of the element is found
 */
fun DoubleArray.hasElement(element: Double): Boolean {
    return KSLArrays.hasElement(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence of the element
 */
fun IntArray.findIndex(element: Int): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return true if an instance of the element is found
 */
fun IntArray.hasElement(element: Int): Boolean {
    return KSLArrays.hasElement(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence of the element
 */
fun LongArray.findIndex(element: Long): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return true if an instance of the element is found
 */
fun LongArray.hasElement(element: Long): Boolean {
    return KSLArrays.hasElement(element, this)
}

/**
 *
 * @return true if the array has at least one 0.0
 */
fun DoubleArray.hasZero(): Boolean {
    return KSLArrays.hasZero(this)
}

/**
 *
 * @return true if the array has at least one 0.0
 */
fun IntArray.hasZero(): Boolean {
    return KSLArrays.hasZero(this)
}

/**
 *
 * @return true if the array has at least one 0.0
 */
fun LongArray.hasZero(): Boolean {
    return KSLArrays.hasZero(this)
}

/**
 * If the array is empty, -1 is returned.
 *
 * @param element the element to search for
 * @return the index of the first occurrence of the element
 */
fun Array<String>.findIndex(element: String): Int {
    return KSLArrays.findIndex(element, this)
}

/**
 * Returns a new array that has been scaled so that the values are between
 * the minimum and maximum values of the supplied array
 *
 * @return the scaled array
 */
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
fun DoubleArray.normScaledArray(): DoubleArray {
    return KSLArrays.normScaledArray(this)
}

/**
 * Copies all but element index of array fromA into array toB
 * If fromA has 1 element, toB will be empty
 * @param index index of element to leave out, must be 0 to fromA.length-1
 * @return a reference to the array toB
 */
fun DoubleArray.copyWithout(index: Int): DoubleArray {
    return KSLArrays.copyWithout(index, this)
}

/**
 * @param c the constant to add to each element
 * @return the transformed array
 */
fun DoubleArray.addConstant(c: Double): DoubleArray {
    return KSLArrays.addConstant(this, c)
}

/**
 * @param c the constant to subtract from each element
 * @return the transformed array
 */
fun DoubleArray.subtractConstant(c: Double): DoubleArray {
    return KSLArrays.subtractConstant(this, c)
}

/**
 * @param c the constant to multiply against each element
 * @return the transformed array
 */
fun DoubleArray.multiplyConstant(c: Double): DoubleArray {
    return KSLArrays.multiplyConstant(this, c)
}

/**
 * @param c the constant to divide each element
 * @return the transformed array
 */
fun DoubleArray.divideConstant(c: Double): DoubleArray {
    return KSLArrays.divideConstant(this, c)
}

/**
 * Multiplies the two arrays element by element. Arrays must have same length.
 *
 * @param b the second array
 * @return the array containing a[i]*b[i]
 */
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
fun Array<DoubleArray>.trimToRectangular(): Array<DoubleArray> {
    return KSLArrays.trimToRectangular(this)
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
fun Array<DoubleArray>.expandToRectangular(fillValue: Double = 0.0): Array<DoubleArray> {
    return KSLArrays.expandToRectangular(this, fillValue)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
fun <T> Array<Array<T>>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
fun Array<DoubleArray>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
fun Array<IntArray>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * A 2-D array is rectangular if all rows have the same number of elements (columns).
 *
 * @return true if the array is rectangular
 */
fun Array<LongArray>.isRectangular(): Boolean {
    return KSLArrays.isRectangular(this)
}

/**
 * Assumes that the array can be ragged. Returns the number of elements in
 * the row array that has the most elements.
 *
 * @return the minimum number of columns in the array
 */
fun Array<DoubleArray>.maxNumColumns(): Int {
    return KSLArrays.minNumColumns(this)
}

/**
 * @param k      the kth column to be extracted (zero based indexing)
 * @return a copy of the extracted column
 */
fun Array<DoubleArray>.column(k: Int): DoubleArray {
    return KSLArrays.column(k, this)
}

/**
 * @param k      the kth column to be extracted (zero based indexing)
 * @return a copy of the extracted column
 */
fun Array<IntArray>.column(k: Int): IntArray {
    return KSLArrays.column(k, this)
}

/**
 * @param k      the kth column to be extracted (zero based indexing)
 * @return a copy of the extracted column
 */
fun Array<LongArray>.column(k: Int): LongArray {
    return KSLArrays.column(k, this)
}

/**
 * @param k      the kth column to be extracted (zero based indexing)
 * @return a copy of the extracted column
 */
fun Array<Array<Any>>.column(k: Int): Array<Any?> {
    return KSLArrays.column(k, this)
}

/**
 * @return a copy of the array
 */
fun Array<DoubleArray>.copyOf(): Array<DoubleArray> {
    return KSLArrays.copy2DArray(this)
}

/**
 * @return a copy of the array
 */
fun Array<IntArray>.copyOf(): Array<IntArray> {
    return KSLArrays.copy2DArray(this)
}

/**
 * @return a copy of the array
 */
fun Array<LongArray>.copyOf(): Array<LongArray> {
    return KSLArrays.copy2DArray(this)
}

/**
 *  Fills the array with the value
 */
fun DoubleArray.fill(theValue : GetValueIfc = ConstantRV.ZERO){
    KSLArrays.fill(this, theValue)
}

/**
 *  Fills the array with the provided value
 */
fun Array<DoubleArray>.fill(theValue : GetValueIfc = ConstantRV.ZERO){
    KSLArrays.fill(this, theValue)
}

/**
 * The destination array is mutated by this method
 *
 * @param col  the column in the destination to fill
 * @param src  the source for filling the column, must not be null
 */
fun Array<DoubleArray>.fillColumn(col: Int, src: DoubleArray) {
    KSLArrays.fillColumn(col, src, this)
}

/**
 * The array must not be null
 *
 * @return the sum of the squares of the elements of the array
 */
fun DoubleArray.sumOfSquares(): Double {
    return KSLArrays.sumOfSquares(this)
}

/**
 * The array must not be null
 *
 * @return the sum of the squares of the elements of the array
 */
fun DoubleArray.sumOfSquareRoots(): Double {
    return KSLArrays.sumOfSquareRoots(this)
}

/**
 * Adds the two arrays element by element. Arrays must have same length and must not be null.
 *
 * @param b the second array
 * @return the array containing a[i]+b[i]
 */
fun DoubleArray.addElements(b: DoubleArray): DoubleArray {
    return KSLArrays.addElements(this, b)
}

/**
 * @param another the second array
 * @return true if all elements are equal
 */
fun DoubleArray.compareTo(another: DoubleArray): Boolean {
    return KSLArrays.compareArrays(this, another)
}

/**
 * Converts any null values to replaceNull. For Array<Double> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
fun Array<Double?>.toPrimitives(replaceNull: Double = 0.0): DoubleArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For List<Double> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
fun List<Double?>.toPrimitives(replaceNull: Double = 0.0): DoubleArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For Array<Int> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
fun Array<Int?>.toPrimitives(replaceNull: Int = 0): IntArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For List<Int> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
fun List<Int?>.toPrimitives(replaceNull: Int = 0): IntArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For Array<Long> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
fun Array<Long?>.toPrimitives(replaceNull: Long = 0): LongArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Converts any null values to replaceNull. For List<Long> use toDoubleArray()
 *
 * @param replaceNull the value to replace any nulls
 * @return the primitive array
 */
fun List<Long?>.toPrimitives(replaceNull: Long = 0): LongArray {
    return KSLArrays.toPrimitives(this, replaceNull)
}

/**
 * Convert the array of double to an array of strings with each element the
 * corresponding value
 *
 * @return the array of strings representing the values of the doubles
 */
fun DoubleArray.toStrings(): Array<String> {
    return KSLArrays.toStrings(this)
}

/**
 * @return a comma delimited string of the array, if empty, returns the empty string
 */
fun DoubleArray.toCSVString(): String {
    return KSLArrays.toCSVString(this)
}

/**
 * @return a comma delimited string of the array, if empty, returns the empty string
 */
fun IntArray.toCSVString(): String {
    return KSLArrays.toCSVString(this)
}

/**
 * @return a comma delimited string of the array, if empty, returns the empty string
 */
fun LongArray.toCSVString(): String {
    return KSLArrays.toCSVString(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
fun IntArray.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
fun Array<Int>.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
fun LongArray.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the array of int to an array of double with each element the
 * corresponding value
 *
 * @return the array of doubles representing the values of the ints
 */
fun Array<Long>.toDoubles(): DoubleArray {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the 2D array of double to a 2D array of Double with each element the
 * corresponding value
 *
 * @return the array of Doubles representing the values of the doubles
 */
fun Array<DoubleArray>.toDoubles(): Array<Array<Double>> {
    return KSLArrays.toDoubles(this)
}

/**
 * Convert the 2D array of Int to a 2D array of Int with each element the
 * corresponding value
 *
 * @return the array of Int representing the values of the doubles
 */
fun Array<IntArray>.toInts(): Array<Array<Int>> {
    return KSLArrays.toInts(this)
}

/**
 * Convert the 2D array of Long to a 2D array of Long with each element the
 * corresponding value
 *
 * @return the array of Long representing the values of the doubles
 */
fun Array<LongArray>.toLongs(): Array<Array<Long>> {
    return KSLArrays.toLongs(this)
}

/**
 * Converts the array of strings to Doubles
 *
 * @param parseFail the fail to use if the parse fails or string is null, by default Double.NaN
 * @return the parsed doubles as an array
 */
fun Array<String?>.parseToDoubles(parseFail: Double = Double.NaN): DoubleArray {
    return KSLArrays.parseToDoubles(this, parseFail)
}

/**
 * Converts the list of strings to Doubles
 *
 * @param parseFail the fail to use if the parse fails or string is null, by default Double.NaN
 * @return the parsed doubles as an array
 */
fun List<String?>.parseToDoubles(parseFail: Double = Double.NaN): DoubleArray {
    return KSLArrays.parseToDoubles(this, parseFail)
}

/**
 * Transposes the n rows by m columns array returned transpose[x][y] = array[y][x]
 *
 * @return an array with n columns and m rows
 */
fun Array<IntArray>.transpose(): Array<IntArray> {
    return KSLArrays.transpose2DArray(this)
}

/**
 * Transposes the n rows by m columns array returned transpose[x][y] = array[y][x]
 *
 * @return an array with n columns and m rows
 */
fun Array<DoubleArray>.transpose(): Array<DoubleArray> {
    return KSLArrays.transpose2DArray(this)
}

/**
 * Transposes the n rows by m columns array returned transpose[x][y] = array[y][x]
 *
 * @return an array with n columns and m rows
 */
fun Array<LongArray>.transpose(): Array<LongArray> {
    return KSLArrays.transpose2DArray(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun DoubleArray.isStrictlyIncreasing(): Boolean {
    return KSLArrays.isStrictlyIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly decreasing a_0 gt a_1 gt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun DoubleArray.isStrictlyDecreasing(): Boolean {
    return KSLArrays.isStrictlyDecreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are increasing a_0 lte a_1 lte a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun DoubleArray.isIncreasing(): Boolean {
    return KSLArrays.isIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are decreasing a_0 gte a_1 gte a_2, etc.
 *
 * @return true if all elements are decreasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun DoubleArray.isDecreasing(): Boolean {
    return KSLArrays.isDecreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are equal a_0 = a_1 = a_2, etc.
 *
 * @return true if all elements are equal, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun DoubleArray.isAllEqual(): Boolean {
    return KSLArrays.isAllEqual(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are different (distinct) a_0 != a_1 != a_2, etc.
 *
 * @return true if all elements are distinct, if there
 * are 0 elements then it returns false
 */
fun DoubleArray.isAllDifferent(): Boolean {
    return KSLArrays.isAllDifferent(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are different (distinct) a_0 != a_1 != a_2, etc.
 *
 * @return true if all elements are distinct, if there
 * are 0 elements then it returns false
 */
fun IntArray.isAllDifferent(): Boolean {
    return KSLArrays.isAllDifferent(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are equal a_0 = a_1 = a_2, etc.
 *
 * @return true if all elements are equal, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun IntArray.isAllEqual(): Boolean {
    return KSLArrays.isAllEqual(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly increasing a_0 lt a_1 lt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun IntArray.isStrictlyIncreasing(): Boolean {
    return KSLArrays.isStrictlyIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are strictly decreasing a_0 gt a_1 gt a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun IntArray.isStrictlyDecreasing(): Boolean {
    return KSLArrays.isStrictlyDecreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are increasing a_0 lte a_1 lte a_2, etc.
 *
 * @return true if all elements are strictly increasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun IntArray.isIncreasing(): Boolean {
    return KSLArrays.isIncreasing(this)
}

/**
 * Examines each element, a_i starting at 0, and determines if all
 * the elements are decreasing a_0 gte a_1 gte a_2, etc.
 *
 * @return true if all elements are decreasing, if there
 * are 0 elements then it returns false, 1 element returns true
 */
fun IntArray.isDecreasing(): Boolean {
    return KSLArrays.isDecreasing(this)
}

/**
 *  Applies the transformation to each element of the array
 *  in place
 */
fun <T> Array<T>.mapInPlace(transform: (T) -> T) {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
}

/**
 *  Applies the transformation to each element of the array
 *  in place
 */
fun IntArray.mapInPlace(transform: (Int) -> Int) {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
}

/**
 *  Applies the transformation to each element of the array
 *  in place
 */

fun DoubleArray.mapInPlace(transform: (Double) -> Double) {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
}
