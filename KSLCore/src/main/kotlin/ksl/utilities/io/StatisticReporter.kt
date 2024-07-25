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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.utilities.io

import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticData
import ksl.utilities.statistic.StatisticIfc
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import java.text.DecimalFormat
import java.util.*

const val DEFAULT_ROW_FORMAT = "%-70s \t %12d \t %12.4f \t %12.4f %n"
const val DEFAULT_HEADER_FORMAT = "%-70s \t %12s \t %12s \t %12s %n"
val D2FORMAT = DecimalFormat(".##")

/**
 * A class to help with making useful statistical reports. Creates summary
 * reports as StringBuilders based on the supplied list of statistics
 *
 * @author rossetti
 * @param listOfStats a list containing the StatisticAccessorIfc instances
 */
class StatisticReporter(listOfStats: MutableList<StatisticIfc> = ArrayList()) {
    private val myStats: MutableList<StatisticIfc>
    private val myRowFormat: StringBuilder
    private val myHeaderFormat: StringBuilder
    private val myNumCols = 120
    private val myNumDecPlaces = 6
    private val myHline: StringBuilder

    /**
     * Indicate whether to have time/date on the report
     * true means yes
     */
    var timeDateFlag = false

    /**
     *
     * true means labeling is on report
     */
    var reportLabelFlag = true

    /**
     * Sets the report title. The title appears as the first line of any report.
     *
     */
    var reportTitle: String? = null

    init {
        myStats = listOfStats
        myHline = StringBuilder()
        for (i in 1..myNumCols) {
            myHline.append("-")
        }
        myRowFormat = StringBuilder(DEFAULT_ROW_FORMAT)
        myHeaderFormat = StringBuilder(DEFAULT_HEADER_FORMAT)
    }

    /**
     * Converts the statistics in the reporter to a data frame
     * containing the statistical data with confidence intervals
     * specified the supplied [level]. The default level is 0.95.
     */
    fun asDataFrame(level: Double = 0.95) : DataFrame<StatisticData> {
        val list = mutableListOf<StatisticData>()
        for(element in myStats){
            list.add(element.statisticData(level))
        }
        return list.toDataFrame()
    }

    override fun toString(): String {
        return summaryReport().toString()
    }

    /**
     *
     * @param name the name of the statistic to add to the reporter
     * @return the created statistic
     */
    fun addStatistic(name: String): Statistic {
        val s = Statistic(name)
        addStatistic(s)
        return s
    }

    /**
     *
     * @param statistic the statistic to add, must not be null
     */
    fun addStatistic(statistic: StatisticIfc) {
        if (myStats.contains(statistic)) {
            return
        }
        myStats.add(statistic)
    }

    /**
     * Finds the number of characters of the statistic with the longest name
     *
     * @return number of characters
     */
    fun findSizeOfLongestName(): Int {
        var max = Int.MIN_VALUE
        var stat: StatisticIfc? = null
        for (s in myStats) {
            if (s.name.length > max) {
                max = s.name.length
                stat = s
            }
        }
        return stat?.name?.length ?: 0
    }

    /**
     * Changes the number of spaces for printing the names
     *
     *
     * @param n must be between 10 and 99
     */
    fun setNameFieldSize(n: Int) {
        require(n >= 10) { "The number of decimal places must be >=10" }
        require(n <= 99) { "The number of decimal places must be <=99" }
        myRowFormat.replace(2, 4, n.toString())
        myHeaderFormat.replace(2, 4, n.toString())
    }

    /**
     * Changes the number of decimal places in the average reporting.
     *
     *
     * @param n must be between 0 and 9
     */
    fun decimalPlaces(n: Int) {
        require(n >= 0) { "The number of decimal places must be >=0" }
        require(n <= 9) { "The number of decimal places must be <=9" }
        myRowFormat.replace(19, 20, n.toString())
        myRowFormat.replace(28, 29, n.toString())
    }

    /**
     * The summary statistics are presented with half-widths
     *
     * @param title optional title of the report
     * @param confLevel confidence level for the half-widths
     * @return a StringBuilder holding the report
     */
    fun halfWidthSummaryReport(title: String? = null, confLevel: Double = 0.95): StringBuilder {
        val sb = StringBuilder()
        val formatter = Formatter(sb)
        if (reportLabelFlag) {
            if (reportTitle != null) {
                formatter.format("%s %n", reportTitle)
            }
            formatter.format("Half-Width Statistical Summary Report")
            formatter.format(" - Confidence Level (%5.3f)%% %n%n", confLevel * 100.0)
        }
        if (timeDateFlag) {
            formatter.format("%tc%n%n", Calendar.getInstance().timeInMillis)
        }
        //formatter.format("Half-Width Confidence Level %4f %n%n", confLevel);
        if (title != null) {
            formatter.format("%s %n", title)
        }
        val hf = myHeaderFormat.toString()
        val rf = myRowFormat.toString()
        formatter.format(hf, "Name", "Count", "Average", "Half-Width")
        formatter.format("%s %n", myHline)
        for (stat in myStats) {
            val n = stat.count.toInt()
            val avg = stat.average
            val hw = stat.halfWidth(confLevel)
            val name = stat.name
            formatter.format(rf, name, n, avg, hw)
        }
        formatter.format("%s %n", myHline)
        return sb
    }

    fun summaryReportAsMarkDown(title: String? = null, df: DecimalFormat?): StringBuilder {
        val sb = StringBuilder()
        val formatter = Formatter(sb)
        if (reportLabelFlag) {
            if (reportTitle != null) {
                formatter.format("%s %n", reportTitle)
            }
            formatter.format("Statistical Summary Report%n")
        }
        if (timeDateFlag) {
            formatter.format("%tc%n%n", Calendar.getInstance().timeInMillis)
        }
        if (title != null) {
            formatter.format("%s %n", title)
        }
        val t = MarkDown.Table(summaryReportHeader(), MarkDown.ColFmt.CENTER)
        for (stat in myStats) {
            t.addRow(summaryReportRow(stat, df))
        }
        sb.append(t)
        sb.append(System.lineSeparator())
        return sb
    }

    fun summaryReportHeader(): List<String> {
        val list: MutableList<String> = ArrayList()
        list.add("Name")
        list.add("Count")
        list.add("Average")
        list.add("Std. Dev.")
        return list
    }

    fun summaryReportRow(statistic: StatisticIfc, df: DecimalFormat? = null): List<String> {
        val list: MutableList<String> = ArrayList()
        list.add(statistic.name)
        val data = doubleArrayOf(
            statistic.count,
            statistic.average,
            statistic.standardDeviation
        )
        list.addAll(KSLArrays.toStrings(data, df).toList())
        return list
    }

    fun halfWidthSummaryReportAsMarkDown(
        title: String? = null,
        level: Double = 0.95,
        df: DecimalFormat? = null
    ): StringBuilder {
        val sb = StringBuilder()
        val formatter = Formatter(sb)
        if (reportLabelFlag) {
            if (reportTitle != null) {
                formatter.format("%s %n", reportTitle)
            }
            formatter.format("**Statistical Summary Report**%n")
        }
        if (timeDateFlag) {
            formatter.format("%tc%n%n", Calendar.getInstance().timeInMillis)
        }
        if (title != null) {
            formatter.format("%s %n", title)
        }
        val t = MarkDown.Table(getHalfWidthSummaryReportHeader(), MarkDown.ColFmt.CENTER)
        for (stat in myStats) {
            t.addRow(getHalfWidthSummaryReportRow(stat, level, df))
        }
        sb.append(t)
        sb.appendLine()
        return sb
    }

    fun getHalfWidthSummaryReportHeader(): List<String> {
        val list: MutableList<String> = ArrayList()
        list.add("Name")
        list.add("Count")
        list.add("Average")
        list.add("Half-Width")
        return list
    }

    fun getHalfWidthSummaryReportRow(
        statistic: StatisticIfc,
        level: Double = 0.95,
        df: DecimalFormat? = null
    ): List<String> {
        val list: MutableList<String> = ArrayList()
        list.add(statistic.name)
        val data = doubleArrayOf(
            statistic.count,
            statistic.average,
            statistic.halfWidth(level)
        )
        list.addAll(KSLArrays.toStrings(data, df).toList())
        return list
    }

    /**
     * Gets the Summary Report as a StringBuilder
     *
     * @param title an optional title for the report
     * @return the StringBuilder
     */
    fun summaryReport(title: String? = null): StringBuilder {
        val sb = StringBuilder()
        val formatter = Formatter(sb)
        if (reportLabelFlag) {
            if (reportTitle != null) {
                formatter.format("%s %n", reportTitle)
            }
            formatter.format("Statistical Summary Report%n")
        }
        if (timeDateFlag) {
            formatter.format("%tc%n%n", Calendar.getInstance().timeInMillis)
        }
        if (title != null) {
            formatter.format("%s %n", title)
        }
        val hf = myHeaderFormat.toString()
        val rf = myRowFormat.toString()
        formatter.format(hf, "Name", "Count", "Average", "Std. Dev.")
        formatter.format("%s %n", myHline)
        for (stat in myStats) {
            val n = stat.count.toInt()
            val avg = stat.average
            val std = stat.standardDeviation
            val name = stat.name
            formatter.format(rf, name, n, avg, std)
        }
        formatter.format("%s %n", myHline)
        return sb
    }

    /**
     * Gets statistics as LaTeX tabular. Each StringBuilder in the list
     * represents a tabular with a maximum number of rows
     *
     *
     * confidence limit is 0.95
     *
     *
     * Response Name Average Std. Dev.
     *
     *
     * Note: If the name has any special LaTeX characters the resulting tabular
     * may not compile.
     *
     * @param maxRows maximum number of rows in each tabular
     * @return a List of StringBuilders
     */
    fun summaryReportAsLaTeXTabular(maxRows: Int = 60): List<StringBuilder> {
        val builders: MutableList<StringBuilder> = ArrayList()
        if (!myStats.isEmpty()) {
            val hline = "\\hline"
            val f1 = "%s %n"
            val f2 = "%s & %6d & %12f & %12f \\\\ %n"
            val header = "Response Name & \$n$ & $ \\bar{x} $ & $ s $ \\\\"
            val beginTabular = "\\begin{tabular}{lcc}"
            val endTabular = "\\end{tabular}"
            var sb = StringBuilder()
            var f = Formatter(sb)
            f.format(f1, beginTabular)
            f.format(f1, hline)
            f.format(f1, header)
            f.format(f1, hline)
            var i = 0
            var mheaders = myStats.size / maxRows
            if (myStats.size % maxRows > 0) {
                mheaders = mheaders + 1
            }
            var nheaders = 1
            var inprogress = false
            for (stat in myStats) {
                val n = stat.count.toInt()
                val avg = stat.average
                val std = stat.standardDeviation
                val name = stat.name
                f.format(f2, name, n, avg, std)
                i++
                inprogress = true
                if (i % maxRows == 0) {
                    // close off current tabular
                    f.format(f1, hline)
                    f.format(f1, endTabular)
                    builders.add(sb)
                    inprogress = false
                    // if necessary, start a new one
                    if (nheaders <= mheaders) {
                        nheaders++
                        sb = StringBuilder()
                        f = Formatter(sb)
                        f.format(f1, beginTabular)
                        f.format(f1, hline)
                        f.format(f1, header)
                        f.format(f1, hline)
                    }
                }
            }
            // close off one in progress
            if (inprogress) {
                f.format(f1, hline)
                f.format(f1, endTabular)
                builders.add(sb)
            }
        }
        return builders
    }

    /**
     * Gets statistics as LaTeX tabular. Each StringBuilder in the list
     * represents a tabular with a maximum number of rows
     *
     *
     * Response Name Average Half-width
     *
     *
     * Note: If the name has any special LaTeX characters the resulting tabular
     * may not compile.
     *
     * @param maxRows maximum number of rows in each tabular
     * @param confLevel the confidence level for the half-width calculation
     * @return a List of StringBuilders
     */
    fun halfWidthSummaryReportAsLaTeXTabular(
        maxRows: Int = 60,
        confLevel: Double = 0.95
    ): List<StringBuilder> {
        val builders: MutableList<StringBuilder> = ArrayList()
        if (!myStats.isEmpty()) {
            val hline = "\\hline"
            val f1 = "%s %n"
            val f2 = "%s & %6d & %12f & %12f \\\\ %n"
            val header = "Response Name & \$n$ & $ \\bar{x} $ & $ hw $ \\\\"
            val beginTabular = "\\begin{tabular}{lcc}"
            val endTabular = "\\end{tabular}"
            var sb = StringBuilder()
            var f = Formatter(sb)
            f.format(f1, beginTabular)
            f.format(f1, hline)
            f.format(f1, header)
            f.format(f1, hline)
            var i = 0
            var mheaders = myStats.size / maxRows
            if (myStats.size % maxRows > 0) {
                mheaders = mheaders + 1
            }
            var nheaders = 1
            var inprogress = false
            for (stat in myStats) {
                val n = stat.count.toInt()
                val avg = stat.average
                val hw = stat.halfWidth(confLevel)
                val name = stat.name
                f.format(f2, name, n, avg, hw)
                i++
                inprogress = true
                if (i % maxRows == 0) {
                    // close off current tabular
                    f.format(f1, hline)
                    f.format(f1, endTabular)
                    builders.add(sb)
                    inprogress = false
                    // if necessary, start a new one
                    if (nheaders <= mheaders) {
                        nheaders++
                        sb = StringBuilder()
                        f = Formatter(sb)
                        f.format(f1, beginTabular)
                        f.format(f1, hline)
                        f.format(f1, header)
                        f.format(f1, hline)
                    }
                }
            }
            // close off one in progress
            if (inprogress) {
                f.format(f1, hline)
                f.format(f1, endTabular)
                builders.add(sb)
            }
        }
        return builders
    }

    /**
     * List of StringBuilder representing LaTeX tables
     *
     * @param maxRows the max number of rows
     * @param confLevel the confidence level
     * @return the tables as StringBuilders
     */
    fun halfWidthSummaryReportAsLaTeXTables(
        maxRows: Int = 60,
        confLevel: Double = 0.95
    ): List<StringBuilder> {
        val list = halfWidthSummaryReportAsLaTeXTabular(maxRows, confLevel)
        val caption = "\\caption{Half-Width Summary Report} \n"
        val captionc = "\\caption{Half-Width Summary Report (continued)} \n"
        val beginTable = "\\begin{table}[ht] \n"
        val endTable = "\\end{table} \n"
        val centering = "\\centering \n"
        var i = 1
        for (sb in list) {
            sb.insert(0, centering)
            if (i == 1) {
                sb.insert(0, caption)
            } else {
                sb.insert(0, captionc)
            }
            i++
            sb.insert(0, beginTable)
            sb.append(endTable)
        }
        return list
    }

    /**
     * List of StringBuilder representing LaTeX tables
     *
     * @param maxRows the maximum number of rows in a table
     * @return the tables as StringBuilders
     */
    fun summaryReportAsLaTeXTables(maxRows: Int = 60): List<StringBuilder> {
        val list = summaryReportAsLaTeXTabular(maxRows)
        val caption = "\\caption{Statistical Summary Report} \n"
        val captionc = "\\caption{Statistical Summary Report (continued)} \n"
        val beginTable = "\\begin{table}[ht] \n"
        val endTable = "\\end{table} \n"
        val centering = "\\centering \n"
        var i = 1
        for (sb in list) {
            sb.insert(0, centering)
            if (i == 1) {
                sb.insert(0, caption)
            } else {
                sb.insert(0, captionc)
            }
            i++
            sb.insert(0, beginTable)
            sb.append(endTable)
        }
        return list
    }

    /**
     * Gets all the statistics in comma separated value format
     *
     * @param header true means 1st line is header
     * @return the csv as a StringBuilder
     */
    fun csvStatistics(header: Boolean = true): StringBuilder {
        val sb = StringBuilder()
        if (!myStats.isEmpty()) {
            if (header) {
                sb.append(myStats[0].csvStatisticHeader)
                sb.append("\n")
            }
        }
        for (stat in myStats) {
            sb.append(stat.csvStatistic)
            sb.append("\n")
        }
        return sb
    }

    /**
     * The confidence intervals for the statistics on the report
     *  with the key being the name of the statistic
     */
    fun confidenceIntervals(level: Double = 0.95): Map<String, Interval>{
        return StatisticReporter.confidenceIntervals(this.myStats, level)
    }

    companion object{

        /**
         *  Converts a list of statistics to a map of confidence intervals
         *  with the key being the name of the statistic
         */
        fun confidenceIntervals(list: List<StatisticIfc>, level: Double = 0.95) : Map<String, Interval>{
            val map = mutableMapOf<String, Interval>()
            for(s in list){
                map[s.name] = s.confidenceInterval(level)
            }
            return map
        }
    }
}

fun main() {
    val n: RVariableIfc = NormalRV()
    val s1 = Statistic("s1")
    val s2 = Statistic("s2 blah")
    s1.collect(n.sample(100))
    s2.collect(n.sample(200))
    val list: MutableList<StatisticIfc> = ArrayList()
    list.add(s1)
    list.add(s2)
    val r = StatisticReporter(list)
    println(r.halfWidthSummaryReport(confLevel = .95))
    println(r.summaryReport())
    //r.setDecimalPlaces(2);
    r.setNameFieldSize(r.findSizeOfLongestName() + 5)
    println(r.halfWidthSummaryReport())
    println(r.findSizeOfLongestName())
    println(r.summaryReportAsLaTeXTabular(5))
    println(r.csvStatistics())
    val out = KSL.createPrintWriter("Report.md")
    out.print(r.halfWidthSummaryReportAsMarkDown())
    out.flush()

    val df = r.asDataFrame()
    println(df)
}