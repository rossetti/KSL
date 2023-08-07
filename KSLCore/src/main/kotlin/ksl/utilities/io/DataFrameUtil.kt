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
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic
import ksl.utilities.toMapOfColumns
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.DataRow
import org.jetbrains.kotlinx.dataframe.api.*
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
        for (row in df.rows()) {
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
    ): DataFrame<T> {
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
     * A new DataColumn is created, such that the first sampleSize elements contain the sampled values.
     * That is, x.get(0), x.get(1), ... , x.get(sampleSize-1) is the random sample without replacement
     *
     * @param T        the type of the data column
     * @param dc          the data column
     * @param sampleSize the size to generate
     * @param stream        the source of randomness
     * @return the new data column of size [sampleSize]
     */
    inline fun <reified T> sampleWithoutReplacement(
        dc: DataColumn<T>,
        sampleSize: Int,
        stream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): DataColumn<T> {
        require(sampleSize <= dc.size()) {
            "Cannot draw without replacement for more than the number of elements ${dc.size()}"
        }
        val m = dc.toList().toMutableList()
        KSLRandom.sampleWithoutReplacement(m, sampleSize, stream)
        return column(m.take(sampleSize))
    }

    /**
     * The data column [dc], is not changed. The returned data column holds
     * a permutation of [dc]
     *
     * @param T        the type of the data type held in the data column
     * @param dc          the data column
     * @param stream        the stream for the source of randomness
     * @return a new data column with a permutation of the rows of [dc]
     */
    inline fun <reified T> permute(
        dc: DataColumn<T>,
        stream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): DataColumn<T> {
        return sampleWithoutReplacement(dc, dc.size(), stream)
    }

    /**
     * The data column [dc], is not changed. The returned data column holds
     * a permutation of [dc]
     *
     * @param T        the type of the data type held in the data column
     * @param dc          the data column
     * @param streamNum        the stream number for the source of randomness
     * @return a new data column with a permutation of the rows of [dc]
     */
    inline fun <reified T> permute(
        dc: DataColumn<T>,
        streamNum: Int
    ): DataColumn<T> {
        return sampleWithoutReplacement(dc, dc.size(), KSLRandom.rnStream(streamNum))
    }

    /**
     * Randomly select an element from the data column
     *
     * @param T  The type of element in the data column
     * @param dc the data column
     * @param stream  the source of randomness
     * @return the randomly selected element
     */
    fun <T> randomlySelect(dc: DataColumn<T>, stream: RNStreamIfc = KSLRandom.defaultRNStream()): T {
        require(dc.size() == 0) { "Cannot select from an empty column" }
        val nRows = dc.size()
        return if (nRows == 1) {
            dc[0]
        } else dc[stream.randInt(0, nRows - 1)]
    }

    /**
     * Randomly select an element from the data column
     *
     * @param T  The type of element in the data column
     * @param dc the data column
     * @param streamNum  the stream number for the source of randomness
     * @return the randomly selected element
     */
    fun <T> randomlySelect(dc: DataColumn<T>, streamNum: Int): T {
        return randomlySelect(dc, KSLRandom.rnStream(streamNum))
    }

    /**
     * Randomly selects an element from the data column using the supplied cdf
     *
     * @param T  the type returned
     * @param dc data column to select from
     * @param cdf  the cumulative probability associated with each element
     * @param stream  the source of randomness
     * @return the randomly selected element
     */
    fun <T> randomlySelect(
        dc: DataColumn<T>,
        cdf: DoubleArray,
        stream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): T {
        // make the row indices array
        val rowIndices = IntRange(0, dc.size()).toMutableList()
        val rowNum = KSLRandom.randomlySelect(rowIndices, cdf, stream)
        return dc[rowNum]
    }

    /**
     * Randomly selects an element from the data column using the supplied cdf
     *
     * @param T  the type returned
     * @param dc data column to select from
     * @param cdf  the cumulative probability associated with each element
     * @param streamNum  the stream number for the source of randomness
     * @return the randomly selected element
     */
    fun <T> randomlySelect(
        dc: DataColumn<T>,
        cdf: DoubleArray,
        streamNum: Int
    ): T {
        return randomlySelect(dc, cdf, KSLRandom.rnStream(streamNum))
    }

    /**
     *  @return the statistics on the column
     */
    fun statistics(dc: DataColumn<Double>): Statistic {
        return Statistic(dc.toDoubleArray())
    }

    /**
     *  @return the histogram on the column
     */
    fun histogram(dc: DataColumn<Double>, breakPoints: DoubleArray = Histogram.recommendBreakPoints(dc.toDoubleArray())): Histogram {
        return Histogram.create(dc.toDoubleArray(), breakPoints)
    }

    /**
     *  @return the statistics on the column
     */
    fun frequencies(dc: DataColumn<Int>): IntegerFrequency {
        val array = dc.toTypedArray().toIntArray()
        val f = IntegerFrequency()
        f.collect(array)
        return f
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
    fun <T> permute(df: DataFrame<T>, streamNum: Int): DataFrame<T> {
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
     * @param T  The type of element in the data frame
     * @param df the data frame
     * @param stream  the source of randomness
     * @return the randomly selected element
     */
    fun <T> randomlySelect(df: DataFrame<T>, stream: RNStreamIfc = KSLRandom.defaultRNStream()): DataRow<T> {
        require(df.isNotEmpty()) { "Cannot select from an empty list" }
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
 *  @return the statistics on the column
 */
fun DataColumn<Double>.statistics(): Statistic {
    return DataFrameUtil.statistics(this)
}

/**
 *  @return the histogram on the column
 */
fun DataColumn<Double>.histogram(breakPoints: DoubleArray = Histogram.recommendBreakPoints(this.toDoubleArray())): Histogram {
    return DataFrameUtil.histogram(this, breakPoints)
}

/**
 *  @return the frequency tabulation on the column
 */
fun DataColumn<Int>.frequencies(): IntegerFrequency {
    return DataFrameUtil.frequencies(this)
}

/**
 *  Converts the 2-D array of doubles to a data frame.
 *  The column names are col1, col2, col3, etc.
 *  The 2D array must be rectangular
 */
fun Array<DoubleArray>.toDataFrame(): DataFrame<*> {
    val map = this.toMapOfLists()
    return map.toDataFrame()
}

fun <T> DataFrame<T>.writeMarkDownTable(writer: PrintWriter = KSL.createPrintWriter("dataFrame.md")) {
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

fun <T> DataFrame<T>.permute(streamNum: Int): DataFrame<T> {
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
fun <T> DataFrame<T>.sampleWithoutReplacement(
    sampleSize: Int,
    stream: RNStreamIfc = KSLRandom.defaultRNStream()
): DataFrame<T> {
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