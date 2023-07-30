/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.io

import ksl.utilities.KSLArrays
import ksl.utilities.isRectangular
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.sample
import ksl.utilities.toMapOfColumns
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.DataRow
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.dataframe.api.isNotEmpty
import org.jetbrains.kotlinx.dataframe.api.rows
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import java.io.PrintWriter
import java.lang.Appendable

object DataFrameUtil {

    /**
     *  Writes the data frame as a MarkDown based table
     *  by converting rows to strings.
     */
    fun <T> buildMarkDown(df: DataFrame<T>, appendable: Appendable) {
        val formats = mutableListOf<MarkDown.ColFmt>()
        for (c in df.columnTypes()) {
            if (c.classifier == String::class) {
                formats.add(MarkDown.ColFmt.LEFT)
            } else {
                formats.add(MarkDown.ColFmt.CENTER)
            }
        }
        val h = MarkDown.tableHeader(df.columnNames(), formats)
        appendable.appendLine(h)
        for (row in df.rows()){
            val values: List<Any?> = row.values()
            val string = values.joinToString()
            val strings: List<String> = string.split(",")
            val line = MarkDown.tableRow(strings)
            appendable.appendLine(line)
        }
    }

    /**
     * The data frame [df], is not changed. The returned data frame holds
     * a sample of the rows from [df]
     *
     * @param T        the type of the data schema held in the data frame
     * @param df          the data frame
     * @param sampleSize the size to generate
     * @param stream        the source of randomness
     * @return a new data frame with the sample from [df] with [sampleSize] rows
     */
    fun <T> sampleWithoutReplacement(
        df: DataFrame<T>,
        sampleSize: Int,
        stream: RNStreamIfc = KSLRandom.defaultRNStream()
    ) : DataFrame<T> {
        require(sampleSize <= df.rowsCount()) {
            "Cannot draw without replacement for more than the number of rows ${df.rowsCount()}"
        }
        // make the row indices array
        val rowIndices = IntRange(0, df.rowsCount()).toMutableList()
        KSLRandom.sampleWithoutReplacement(rowIndices, sampleSize, stream)
        val ri = rowIndices.subList(0, sampleSize)
        return df[ri]
    }

    /**
     * The data frame [df], is not changed. The returned data frame holds
     * a sample of the rows from [df]
     *
     * @param T        the type of the data schema held in the data frame
     * @param df          the data frame
     * @param sampleSize the size to generate
     * @param streamNum        the stream number for the source of randomness
     * @return a new data frame with the sample from [df] with [sampleSize] rows
     */
    fun <T> sampleWithoutReplacement(df: DataFrame<T>, sampleSize: Int, streamNum: Int): DataFrame<T> {
        return sampleWithoutReplacement(df, sampleSize, KSLRandom.rnStream(streamNum))
    }

    /**
     * The data frame [df], is not changed. The returned data frame holds
     * a permutation of [df]
     *
     * @param T        the type of the data schema held in the data frame
     * @param df          the data frame
     * @param streamNum        the stream number for the source of randomness
     * @return a new data frame with a permutation of the rows of [df]
     */
    fun <T> permute(df: DataFrame<T>, streamNum: Int) : DataFrame<T> {
        return permute(df, KSLRandom.rnStream(streamNum))
    }

    /**
     * The data frame [df], is not changed. The returned data frame holds
     * a permutation of [df]
     *
     * @param T        the type of the data schema held in the data frame
     * @param df          the data frame
     * @param stream        the stream for the source of randomness
     * @return a new data frame with a permutation of the rows of [df]
     */
    fun <T> permute(df: DataFrame<T>, stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataFrame<T> {
        return sampleWithoutReplacement(df, df.rowsCount(), stream)
    }

    /**
     * Randomly select a row from the data frame
     *
     * @param T       The type of element in the data frame
     * @param df      the data frame
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected row
     */
    fun <T> randomlySelect(df: DataFrame<T>, streamNum: Int): DataRow<T> {
        return randomlySelect(df, KSLRandom.rnStream(streamNum))
    }

    /**
     * Randomly select a row from the data frame
     *
     * @param T  The type of element in the data fram
     * @param df the data frame
     * @param stream  the source of randomness
     * @return the randomly selected element
     */
    fun <T> randomlySelect(df: DataFrame<T>, stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataRow<T> {
        require(df.isNotEmpty()){"Cannot select from an empty list"}
        val nRows = df.rowsCount()
        return if (nRows == 1) {
            df[0]
        } else df[stream.randInt(0, nRows - 1)]
    }

    /**
     * Randomly selects a row from the data frame using the supplied cdf
     *
     * @param T       the type returned
     * @param df      data frame to select from
     * @param cdf       the cumulative probability associated with each element of array
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected row
     */
    fun <T> randomlySelect(df: DataFrame<T>, cdf: DoubleArray, streamNum: Int): DataRow<T> {
        return randomlySelect(df, cdf, KSLRandom.rnStream(streamNum))
    }

    /**
     * Randomly selects from the data frame using the supplied cdf
     *
     * @param T  the type returned
     * @param df data frame to select from
     * @param cdf  the cumulative probability associated with each element of
     * array
     * @param stream  the source of randomness
     * @return the randomly selected row
     */
    fun <T> randomlySelect(
        df: DataFrame<T>,
        cdf: DoubleArray,
        stream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): DataRow<T> {
        // make the row indices array
        val rowIndices = IntRange(0, df.rowsCount()).toMutableList()
        val rowNum = KSLRandom.randomlySelect(rowIndices, cdf, stream)
        return df[rowNum]
    }
}

/**
 *  Converts the 2-D array of doubles to a data frame.
 *  The column names are col1, col2, col3, etc.
 *  The 2D array must be rectangular
 */
fun Array<DoubleArray>.toDataFrame() : DataFrame<*> {
    val map = this.toMapOfLists()
    return map.toDataFrame()
}

fun <T> DataFrame<T>.writeMarkDownTable(writer: PrintWriter = KSL.createPrintWriter("dataFrame.md")){
    DataFrameUtil.buildMarkDown(this, writer)
    writer.flush()
}

fun <T> DataFrame<T>.asMarkDownTable(): String {
    val sb = StringBuilder()
    DataFrameUtil.buildMarkDown(this, sb)
    return sb.toString()
}

fun <T> DataFrame<T>.randomlySelect(stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataRow<T> {
    return DataFrameUtil.randomlySelect(this, stream)
}

fun <T> DataFrame<T>.randomlySelect(streamNum: Int): DataRow<T> {
    return DataFrameUtil.randomlySelect(this, streamNum)
}

fun <T> DataFrame<T>.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataFrame<T> {
    return DataFrameUtil.permute(this, stream)
}

fun <T> DataFrame<T>.permute(streamNum: Int): DataFrame<T>{
    return DataFrameUtil.permute(this, streamNum)
}

/** Randomly samples a row from the data frame (with replacement).
 *
 * @param stream the stream to use for randomness
 */
fun <T> DataFrame<T>.sample(stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataRow<T> {
    return this[stream.randInt(0, this.rowsCount() - 1)]
}

/**
 * The data frame, is not changed. The returned data frame holds
 * a sample of the rows.
 *
 * @param T        the type of the data schema held in the data frame
 * @param sampleSize the size to generate
 * @param streamNum        the stream number for the source of randomness
 * @return a new data frame with the sample with [sampleSize] rows
 */
fun <T> DataFrame<T>.sampleWithoutReplacement(sampleSize: Int, streamNum: Int): DataFrame<T> {
    return DataFrameUtil.sampleWithoutReplacement(this, sampleSize, KSLRandom.rnStream(streamNum))
}

/**
 * The data frame, is not changed. The returned data frame holds
 * a sample of the rows.
 *
 * @param T        the type of the data schema held in the data frame
 * @param sampleSize the size to generate
 * @param stream        the stream number for the source of randomness
 * @return a new data frame with the sample [sampleSize] rows
 */
fun <T> DataFrame<T>.sampleWithoutReplacement(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataFrame<T> {
    return DataFrameUtil.sampleWithoutReplacement(this, sampleSize, stream)
}

/**
 * The data frame, is not changed. The returned data frame holds
 * a sample of the rows, with replacement. The rows may repeat.
 *
 * @param T        the type of the data schema held in the data frame
 * @param sampleSize the size to generate
 * @param stream        the stream number for the source of randomness
 * @return a new data frame with the sample [sampleSize] rows
 */
fun <T> DataFrame<T>.sample(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataFrame<T> {
    val rowIndices = IntRange(0, this.rowsCount()).toMutableList()
    val sample: MutableList<Int> = rowIndices.sample(sampleSize, stream)
    return this[sample]
}