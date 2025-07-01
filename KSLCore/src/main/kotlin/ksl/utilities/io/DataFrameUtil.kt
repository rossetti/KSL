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

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package ksl.utilities.io

import ksl.controls.experiments.LinearModel
import ksl.utilities.KSLArrays
import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.TabularFile
import ksl.utilities.io.tabularfiles.TabularOutputFile
import ksl.utilities.isRectangular
import ksl.utilities.collections.toMapOfLists
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.sample
import ksl.utilities.statistic.*
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.*
import org.jetbrains.kotlinx.dataframe.annotations.DataSchema
import org.jetbrains.kotlinx.dataframe.api.*
import java.io.PrintWriter
import java.lang.Appendable
import java.nio.file.Path

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
    fun statistics(dc: DataColumn<Double>, name: String? = dc.name()): Statistic {
        return Statistic(name, dc.toDoubleArray())
    }

    /**
     *  @return the histogram on the column
     */
    fun histogram(
        dc: DataColumn<Double>,
        breakPoints: DoubleArray = Histogram.recommendBreakPoints(dc.toDoubleArray()),
        name: String? = dc.name()
    ): Histogram {
        return Histogram.create(dc.toDoubleArray(), breakPoints, name)
    }

    /**
     *  @param dc the column to count
     *  @param name an optional name for the returned frequencies
     *  @return the frequencies of the integers in the column
     */
    fun frequenciesI(dc: DataColumn<Int>, name: String? = dc.name()): IntegerFrequency {
        val array = dc.toTypedArray().toIntArray()
        val f = IntegerFrequency(name = name)
        f.collect(array)
        return f
    }

    /**
     *  @param dc the column to count
     *  @param name an optional name for the returned frequencies
     *  @return converts the double values to integers and then returns
     *  the frequencies of the integers in the column
     */
    fun frequenciesD(dc: DataColumn<Double>, name: String? = dc.name()): IntegerFrequency {
        val array = dc.toTypedArray().toDoubleArray()
        val f = IntegerFrequency(name = name)
        f.collect(array)
        return f
    }

    /**
     *  @return a box plot summary for the column
     */
    fun boxPlotSummary(dc: DataColumn<Double>, name: String? = dc.name()): BoxPlotSummary {
        return BoxPlotSummary(dc.toDoubleArray(), name)
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

    /**
     *  Converts the data frame to rows of comma separated file output
     *  with specified separator. The default [separator] is a comma.
     *  Elements that are strings are enclosed in double quotes to permit
     *  the separator to appear in the element. If the [header] is true,
     *  then the column names of the dataframe are included as the first row
     *  before any data rows are appended.
     */
    fun toCSV(
        df: AnyFrame,
        appendable: Appendable,
        header: Boolean = true,
        separator: String = ","
    ) {
        if (header) {
            appendable.appendLine(df.columnNames().joinToString(separator) { "\"$it\"" })
        }
        for (row in df) {
            appendable.appendLine(row.toCSV(separator))
        }
    }

    /**
     *  Convert the dataframe to a TabularOutputFile with the supplied
     *  file name within KSL.outDir
     */
    fun toTabularFile(df: AnyFrame, fileName: String): TabularOutputFile {
        return toTabularFile(df, KSL.outDir.resolve(fileName))
    }

    /**
     *  Convert the dataframe to a TabularOutputFile at the supplied [pathToFile]
     */
    fun toTabularFile(df: AnyFrame, pathToFile: Path): TabularOutputFile {
        val cNames = df.columnNames()
        val cTypes = df.columnTypes()
        val dTypes = TabularFile.toDataTypes(cTypes)
        val cols = mutableMapOf<String, DataType>()
        for ((i, n) in cNames.withIndex()) {
            cols[n] = dTypes[i]
        }
        val tof = TabularOutputFile(cols, pathToFile)
        val rs = tof.row()
        for (row in df) {
            rs.setElements(row.values())
            tof.writeRow(rs)
        }
        tof.flushRows()
        return tof
    }
}

/**
 *  Checks if the column holds type Double
 */
internal fun AnyCol.isDoubleColumn(): Boolean {
    return typeClass == Double::class
}

/**
 *  Checks if all the columns in the list hold Double values
 */
internal fun List<AnyCol>.allDoubleColumns(): Boolean {
    for (col in this) {
        if (!col.isDoubleColumn()) return false
    }
    return true
}

/**
 *  Converts the list of AnyCol to a list of DataColumn<Double>
 *  Any column in the list that hold Double type will be added
 *  to the returned list. Columns that do not hold Double type
 *  will not be added to the list.
 */
internal fun List<AnyCol>.toDoubleColumns(): List<DataColumn<Double>> {
    val list = mutableListOf<DataColumn<Double>>()
    for (col in this) {
        // this cast should be safe because of the column type test
        @Suppress("UNCHECKED_CAST")
        if (col.isDoubleColumn()) list.add(col as DataColumn<Double>)
    }
    return list
}

/**
 *  True if the dataframe contains all the named columns in the list.
 *  False if at least one column is not in the dataframe.
 */
fun AnyFrame.hasAllNamedColumns(columnNames: List<String>): Boolean {
    for (col in columnNames) {
        if (!this.containsColumn(col)) return false
    }
    return true
}

/**
 *  True if the dataframe contains all the columns in the list.
 *  False if at least one column is not in the dataframe.
 */
fun AnyFrame.hasAllColumns(list: List<AnyCol>): Boolean {
    for (col in list) {
        if (!this.containsColumn(col.name())) return false
    }
    return true
}

/**
 *  Causes a new column to be added to the dataframe that represents the
 *  element-wise multiplication of column A and B. The columns must be in the
 *  data frame and be of type DataColumn<Double>
 */
fun AnyFrame.multiply(colAName: String, colBName: String): AnyFrame {
    return multiplyColumns(listOf(colAName, colBName))
}

/**
 *  Causes a new column to be added to the dataframe that represents the
 *  element-wise multiplication of column A and B. The columns must be in the dataframe.
 */
fun AnyFrame.multiply(colA: DataColumn<Double>, colB: DataColumn<Double>): AnyFrame {
    return multiply(listOf(colA, colB))
}

/**
 *  Causes a new column to be added to the dataframe that represents the
 *  element-wise multiplication of the columns in the list. The columns must be in the dataframe
 *  and the columns must hold Double values.
 */
fun AnyFrame.multiplyColumns(columnNames: List<String>): AnyFrame {
    require(columnNames.size >= 2) { "There must be at least two columns to multiply." }
    val list = mutableListOf<DataColumn<Double>>()
    for (name in columnNames) {
        require(containsColumn(name)) { "column $name was not in the data frame" }
        val col = getColumn(name)
        require(col.typeClass == Double::class) { "column ${col.name()} is not a double column" }
        @Suppress("UNCHECKED_CAST")
        list.add(col as DataColumn<Double>)
    }
    return multiply(list)
}

/**
 *   Causes the columns associated with the linear model to be added to the
 *   dataframe.
 *   @param linearModel the linear model specified with the column names. Columns
 *   representing the main effects must already be in the dataframe
 *   @return the new dataframe with the additional columns
 */
fun AnyFrame.addColumnsFor(linearModel: LinearModel) : AnyFrame {
    // require that base columns exist
    require(hasAllNamedColumns(linearModel.mainEffects.toList())){"There were missing named columns in the dataframe"}
    var df = this
    for(cn in linearModel.termsAsList){
        if (cn.size >= 2){
            df = df.multiplyColumns(cn)
        }
    }
    return df
}

/**
 *  Causes a new column to be added to the dataframe that represents the
 *  element-wise multiplication of the columns in the list. The columns must be in the dataframe.
 */
fun AnyFrame.multiply(columns: List<DataColumn<Double>>): AnyFrame {
    require(hasAllColumns(columns)) { "A supplied column was not part of the dataframe!" }
    require(columns.size >= 2) { "There must be at least two columns!" }
    val list = mutableListOf<Double>()
    for (row in this) {
        var p = 1.0
        for (col in columns) {
            val r = row.getValueOrNull(col.name()) ?: Double.NaN
            p = p * r
        }
        list.add(p)
    }
    val sb = StringBuilder()
    for (col in columns) {
        sb.append("${col.name()}*")
    }
    return add(list.toColumn(sb.toString().dropLast(1)))
}

/**
 *  Convert the dataframe to a TabularOutputFile with the supplied
 *  file name within KSL.outDir
 */
fun AnyFrame.toTabularFile(fileName: String): TabularFile {
    return DataFrameUtil.toTabularFile(this, fileName)
}

/**
 *  Convert the dataframe to a TabularOutputFile at the supplied [path]
 */
fun AnyFrame.toTabularFile(pathToFile: Path): TabularFile {
    return DataFrameUtil.toTabularFile(this, pathToFile)
}

/**
 *  Creates a dataframe that holds the statistical data for
 *  any column that holds Double type within the original dataframe.
 *  The confidence interval [level] is by default 0.95.
 */
fun AnyFrame.statistics(level: Double = 0.95): DataFrame<StatisticData> {
    val list = mutableListOf<StatisticData>()
    for (c in columns()) {
        if (c.typeClass == Double::class) {
            @Suppress("UNCHECKED_CAST")
            val ct = c as DataColumn<Double>
            list.add(ct.statistics().statisticData(level))
        }
    }
    return list.toDataFrame()
}

/**
 *  Creates a dataframe that holds the box plot summary data for
 *  any column that holds Double type within the original dataframe.
 */
fun AnyFrame.boxPlotSummaryData(): DataFrame<BoxPlotDataIfc> {
    val list = mutableListOf<BoxPlotDataIfc>()
    for (c in columns()) {
        if (c.typeClass == Double::class) {
            @Suppress("UNCHECKED_CAST")
            val ct = c as DataColumn<Double>
            list.add(ct.boxPlotSummary())
        }
    }
    var df = list.toDataFrame()
    df = df.move("name").to(0)
    df = df.move("count").to(1)
    df = df.move("min").to(2)
    df = df.move("firstQuartile").to(3)
    df = df.move("median").to(4)
    df = df.move("thirdQuartile").to(5)
    df = df.move("max").to(6)
    return df
}

/**
 *  Creates a dataframe that holds the summary statistical data for
 *  any column that holds Double type within the original dataframe.
 */
fun AnyFrame.summaryStatistics(): DataFrame<SummaryStatisticsIfc> {
    val list = mutableListOf<SummaryStatisticsIfc>()
    for (c in columns()) {
        if (c.typeClass == Double::class) {
            @Suppress("UNCHECKED_CAST")
            val ct = c as DataColumn<Double>
            list.add(ct.statistics())
        }
    }
    var df = list.toDataFrame()
    df = df.move("name").to(0)
    df = df.move("count").to(1)
    df = df.move("average").to(2)
    df = df.move("standardDeviation").to(3)
    df = df.move("variance").to(4)
    df = df.move("min").to(5)
    df = df.move("max").to(6)
    return df
}

/**
 *  Converts the data frame to rows of comma separated file output
 *  with specified separator. The default [separator] is a comma.
 *  Elements that are strings are enclosed in double quotes to permit
 *  the separator to appear in the element. If the [header] is true,
 *  then the column names of the dataframe are included as the first row
 *  before any data rows are appended.
 */
fun AnyFrame.toCSV(
    appendable: Appendable,
    header: Boolean = true,
    separator: String = ","
) {
    return DataFrameUtil.toCSV(this, appendable, header, separator)
}

/**
 *  Converts the DataRow to a string separated by the provided [separator].
 *  The default is common separated. Elements in the row that are strings
 *  are enclosed in double quotes to permit the separator to appear in the string.
 */
fun AnyRow.toCSV(separator: String = ","): String {
    return values().joinToString(separator) { if (it is String) "\"$it\"" else it.toString() }
}

@DataSchema
interface StatSchema {
    val statName: String
    val statValue: Double
}

/**
 *  Converts the box plot summary data to a data frame with two columns.
 *  The first column holds the name of the statstics and the second column
 *  holds the values.
 *
 */
fun BoxPlotSummary.toDataFrame(): DataFrame<BoxPlotDataIfc> {
    val map = this.asMap()
    val c1 = column(map.keys) named "Box Plot Statistic"
    val c2 = column(map.values) named "Value"
    return dataFrameOf(c1, c2).cast()
}

/**
 *  Converts a statistic to a data frame with two columns.
 *  The first column holds the names of the statistics and the
 *  second column holds the values. The [valueLabel] can be used
 *  to provide a column name for the value columns. By default,
 *  it is "Value".
 */
fun StatisticIfc.toStatDataFrame(valueLabel: String = "Value"): DataFrame<StatSchema> {
    val map = this.statisticsAsMap
    val c1 = column(map.keys) named "Statistic"
    val c2 = column(map.values) named valueLabel
    return dataFrameOf(c1, c2).cast()
}

/**
 *  Converts the integer frequency data into a dataframe representation
 */
fun IntegerFrequencyIfc.toDataFrame(): DataFrame<FrequencyData> {
    return this.frequencyData().toDataFrame()
}

/**
 *  Converts the histogram bin data into a dataframe representation
 */
fun HistogramIfc.toDataFrame(): DataFrame<HistogramBinData> {
    return this.histogramData().toDataFrame()
}

/**
 *  @return the statistics on the column
 */
fun DataColumn<Double>.statistics(name: String? = this.name()): Statistic {
    return DataFrameUtil.statistics(this, name)
}

/**
 *  @return the histogram on the column
 */
fun DataColumn<Double>.histogram(
    breakPoints: DoubleArray = Histogram.recommendBreakPoints(this.toDoubleArray()),
    name: String? = this.name()
): Histogram {
    return DataFrameUtil.histogram(this, breakPoints, name)
}

/**
 *  @return the box plot summary on the column
 */
fun DataColumn<Double>.boxPlotSummary(name: String? = this.name()): BoxPlotSummary {
    return DataFrameUtil.boxPlotSummary(this, name)
}

/**
 *  @return the frequency tabulation on the column
 */
fun DataColumn<Int>.frequenciesI(name: String? = this.name()): IntegerFrequency {
    return DataFrameUtil.frequenciesI(this, name)
}

/**
 *  @return the frequency tabulation on the column
 */
fun DataColumn<Double>.frequenciesD(name: String? = this.name()): IntegerFrequency {
    return DataFrameUtil.frequenciesD(this, name)
}

/**
 *  Converts the 2-D array of doubles to a data frame.
 *  The column names are col1, col2, col3, etc.
 *  The 2D array must be rectangular
 */
fun Array<DoubleArray>.toDataFrame(): AnyFrame {
    require(this.isRectangular()) { "The array must be rectangular" }
    val map = this.toMapOfLists()
    return map.toDataFrame()
}

/**
 *  Converts the data stored in each array to columns within
 *  a DataFrame, with the column names as the key from the map and
 *  the columns holding the data. Each array must have the same size.
 */
fun Map<String, DoubleArray>.toDataFrame(): AnyFrame {
    val arrays = this.values.toList()
    val da = Array(this.size) { arrays[it] }
    require(KSLArrays.isRectangular(da)) { "The arrays must all have the same size" }
    return toMapOfLists().toDataFrame()
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
