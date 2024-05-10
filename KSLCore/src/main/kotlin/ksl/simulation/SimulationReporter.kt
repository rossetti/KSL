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
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.simulation

import ksl.observers.textfile.CSVExperimentReport
import ksl.observers.textfile.CSVReplicationReport
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.StatisticReporter
import ksl.utilities.statistic.StatisticIfc
import java.io.PrintWriter
import java.nio.file.Path
import java.text.DecimalFormat
import java.util.*

/**
 * This class facilitates simulation output reporting. There are two main
 * reporting functions: within replication statistics and across replication
 * statistics.  The class automatically reports within replication and across
 * replication statistics to comma separated value files by attaching
 * observers to the model.  If you do not want this automated output, then
 * you should use the appropriate turnOffXXX methods.
 *
 * This class attaches a CSVReplicationReport to the model for collection purposes. If the simulation
 * is run multiple times, then statistical data continues to be observed by the
 * CSVReplicationReport, unless it is turned off. Thus, data across many experiments can be captured in
 * this manner. This produces a comma separated value file containing all end of
 * replication statistical summaries for every counter and response variable in
 * the model.
 *
 * There are a number of options available if you want to capture across
 * replication statistics.
 *
 * The class uses a CSVExperimentReport to observe the model. This produces a comma separated value file containing all across
 * replication statistical summaries for every counter and response variable in the model.
 *
 * Use any of the writeAcrossReplicationX() methods. These methods
 * will write across replication summary statistics to files, standard output,
 * LaTeX, CSV, etc.
 *
 * @author rossetti
 */
class SimulationReporter(theModel: Model) {

    private val model: Model = theModel
    private val experiment: ExperimentIfc = theModel.myExperiment
//    private lateinit var myCSVRepReport: CSVReplicationReport
//    private lateinit var myCSVExpReport: CSVExperimentReport

    init {
//        if (autoCSVReports){
//            myCSVRepReport = CSVReplicationReport(theModel)
//            myCSVExpReport = CSVExperimentReport(theModel)
//        }
    }

    /**
     * A convenience method for subclasses. Gets the response variables from
     * the model
     *
     * @return the list
     */
    val responses
        get() = model.responses

    /**
     * A convenience method for subclasses. Gets the counters from the model
     *
     * @return the list
     */
    val counters
        get() = model.counters

    /**
     * Uses a StringBuilder to hold the across replication statistics formatted
     * as a comma separated values with an appropriate header
     *
     * @return the string builder
     */
    fun acrossReplicationCSVStatistics(): StringBuilder {
        val sb = StringBuilder()
        var header = true
        if (responses.isNotEmpty()) {
            for (r in responses) {
                val stat = r.acrossReplicationStatistic
                if (header) {
                    header = false
                    sb.append("SimName, ModelName, ExpName, ResponseType, ResponseID, ResponseName,")
                    sb.append(stat.csvStatisticHeader)
                    sb.appendLine()
                }
                if (r.defaultReportingOption) {
                    sb.append(model.simulationName)
                    sb.append(",")
                    sb.append(model.name)
                    sb.append(",")
                    sb.append(model.experimentName)
                    sb.append(",")
                    sb.append(r::class.simpleName)
                    sb.append(",")
                    sb.append(r.id)
                    sb.append(",")
                    sb.append(r.name)
                    sb.append(",")
                    sb.append(stat.csvStatistic)
                    sb.appendLine()
                }
            }
        }
        if (counters.isNotEmpty()) {
            for (c in counters) {
                val stat = c.acrossReplicationStatistic
                if (header) {
                    header = false
                    sb.append("SimName, ModelName, ExpName, ResponseType, ResponseID, ResponseName,")
                    sb.append(stat.csvStatisticHeader)
                    sb.appendLine()
                }
                if (c.defaultReportingOption) {
                    sb.append(model.simulationName)
                    sb.append(",")
                    sb.append(model.name)
                    sb.append(",")
                    sb.append(model.experimentName)
                    sb.append(",")
                    sb.append(c::class.simpleName)
                    sb.append(",")
                    sb.append(c.id)
                    sb.append(",")
                    sb.append(c.name)
                    sb.append(",")
                    sb.append(stat.csvStatistic)
                    sb.appendLine()
                }
            }
        }
        return sb
    }

    /**
     * Writes the across replication statistics to the supplied PrintWriter as
     * comma separated value output
     *
     * @param out the PrintWriter
     */
    fun writeAcrossReplicationCSVStatistics(out: PrintWriter) {
        var header = true
        if (responses.isNotEmpty()) {
            for (r in responses) {
                val stat = r.acrossReplicationStatistic
                if (header) {
                    header = false
                    out.print("SimName, ModelName, ExpName, ResponseType, ResponseID, ResponseName,")
                    out.println(stat.csvStatisticHeader)
                }
                if (r.defaultReportingOption) {
                    out.print(model.simulationName + ",")
                    out.print(model.name + ",")
                    out.print(model.experimentName + ",")
                    out.print(r::class.simpleName + ",")
                    out.print(r.id.toString() + ",")
                    out.print(r.name + ",")
                    out.println(stat.csvStatistic)
                }
            }
        }
        if (counters.isNotEmpty()) {
            for (c in counters) {
                val stat = c.acrossReplicationStatistic
                if (header) {
                    header = false
                    out.print("SimName, ModelName, ExpName, ResponseType, ResponseID, ResponseName,")
                    out.println(stat.csvStatisticHeader)
                }
                if (c.defaultReportingOption) {
                    out.print(model.simulationName + ",")
                    out.print(model.name + ",")
                    out.print(model.experimentName + ",")
                    out.print(c::class.simpleName + ",")
                    out.print(c.id.toString() + ",")
                    out.print(c.name + ",")
                    out.println(stat.csvStatistic)
                }
            }
        }
    }

    /**
     * Writes the full across replication statistics to the supplied PrintWriter as
     * text output.  Full means all statistical quantities are printed for every statistic
     *
     * @param out the PrintWriter
     */
    fun writeFullAcrossReplicationStatistics(out: PrintWriter) {
        out.println("-------------------------------------------------------")
        out.println()
        out.println(Date())
        out.print("Simulation Results for Model: ")
        out.println(model.name)
        out.println()
        out.println("-------------------------------------------------------")
        for (r in responses) {
            if (r.defaultReportingOption) {
                val stat = r.acrossReplicationStatistic
                out.println(stat)
            }
        }
        for (c in counters) {
            if (c.defaultReportingOption) {
                val stat = c.acrossReplicationStatistic
                out.println(stat)
            }
        }
        out.println("-------------------------------------------------------")
    }

    /**
     * Writes shortened across replication statistics to the supplied
     * PrintWriter as text output
     *
     * Response Name Average Std. Dev.
     *
     * @param out the PrintWriter
     */
    fun writeAcrossReplicationSummaryStatistics(out: PrintWriter) {
        Objects.requireNonNull(out, "The PrintWriter was null!")
        val hline = "-------------------------------------------------------------------------------"
        out.println(hline)
        out.println()
        out.println("Across Replication Statistical Summary Report")
        out.println(Date())
        out.print("Simulation Results for Model: ")
        out.println(model.name)
        out.println()
        out.println()
        out.print("Number of Replications: ")
        out.println(model.currentReplicationNumber)
        out.print("Length of Warm up period: ")
        out.println(experiment.lengthOfReplicationWarmUp)
        out.print("Length of Replications: ")
        out.println(experiment.lengthOfReplication)
        val format = "%-30s \t %12f \t %12f \t %12f %n"
        if (responses.isNotEmpty()) {
            out.println(hline)
            out.println("Response Variables")
            out.println(hline)
            out.printf("%-30s \t %12s \t %12s \t %5s %n", "Name", "Average", "Std. Dev.", "Count")
            out.println(hline)
            for (r in responses) {
                val stat = r.acrossReplicationStatistic
                if (r.defaultReportingOption) {
                    val avg = stat.average
                    val std = stat.standardDeviation
                    val n = stat.count
                    val name = r.name
                    out.printf(format, name, avg, std, n)
                }
            }
            out.println(hline)
        }
        if (!counters.isEmpty()) {
            out.println()
            out.println(hline)
            out.println("Counters")
            out.println(hline)
            out.printf("%-30s \t %12s \t %12s \t %5s %n", "Name", "Average", "Std. Dev.", "Count")
            out.println(hline)
            for (c in counters) {
                val stat = c.acrossReplicationStatistic
                if (c.defaultReportingOption) {
                    val avg = stat.average
                    val std = stat.standardDeviation
                    val n = stat.count
                    val name = c.name
                    out.printf(format, name, avg, std, n)
                }
            }
            out.println(hline)
        }
    }

    /**
     * Gets the across replication statistics as a list
     *
     * @return a list filled with the across replication statistics
     */
    fun acrossReplicationStatisticsList(): List<StatisticIfc> {
            val list: MutableList<StatisticIfc> = ArrayList()
            fillAcrossReplicationStatistics(list)
            return list
        }

    /** Fills the supplied list with the across replication statistics
     *
     * @param list the list to fill
     */
    fun fillAcrossReplicationStatistics(list: MutableList<StatisticIfc>) {
        fillResponseReplicationStatistics(list)
        fillCounterAcrossReplicationStatistics(list)
    }

    /** Fills the list with across replication statistics from the response
     * variables (Response and TWResponse).
     *
     * @param list the list to fill
     */
    fun fillResponseReplicationStatistics(list: MutableList<StatisticIfc>) {
        for (r in responses) {
            if (r.defaultReportingOption) {
                val stat = r.acrossReplicationStatistic
                list.add(stat)
            }
        }
    }

    /** Fills the list with across replication statistics from the Counters
     *
     * @param list the list to fill
     */
    fun fillCounterAcrossReplicationStatistics(list: MutableList<StatisticIfc>) {
        for (c in counters) {
            if (c.defaultReportingOption) {
                val stat = c.acrossReplicationStatistic
                list.add(stat)
            }
        }
    }

    /**
     * Fills the StringBuilder with across replication statistics
     *
     *
     * @param sb the StringBuilder to fill
     */
    fun acrossReplicationStatistics(sb: StringBuilder = StringBuilder()) : StringBuilder {
        sb.append(Date())
        sb.append(System.lineSeparator())
        sb.append("Simulation Results for Model:")
        sb.append(System.lineSeparator())
        sb.append(model.name)
        sb.append(System.lineSeparator())
        sb.append(this)
        sb.append(System.lineSeparator())
        for (r in responses) {
            if (r.defaultReportingOption) {
                val stat = r.acrossReplicationStatistic
                sb.append(stat)
                sb.appendLine()
            }
        }
        for (c in counters) {
            if (c.defaultReportingOption) {
                val stat = c.acrossReplicationStatistic
                sb.append(stat)
                sb.appendLine()
            }
        }
        return sb
    }

    /**
     * Prints the across replication statistics as comma separated values
     *
     */
    fun printAcrossReplicationCSVStatistics() {
        writeAcrossReplicationCSVStatistics(PrintWriter(System.out, true))
    }

    /**
     * Creates a PrintWriter with the supplied name in directory jslOutput and
     * writes out the across replication statistics
     *
     * @param pathToFile the Path to the file
     * @return the PrintWriter
     */
    fun writeAcrossReplicationCSVStatistics(pathToFile: Path): PrintWriter {
        val out = KSLFileUtil.createPrintWriter(pathToFile)
        writeAcrossReplicationCSVStatistics(out)
        return out
    }

    /**
     * Creates a PrintWriter with the supplied name in default output directory for the simulation
     * writes out the across replication statistics
     *
     * @param fName the file name
     * @return the PrintWriter so that additional information can be written to the file
     */
    fun writeAcrossReplicationCSVStatistics(fName: String = model.simulationName + "_AcrossRepCSVStatistics.csv"): PrintWriter {
        val path = model.outputDirectory.outDir.resolve(fName)
        return writeAcrossReplicationCSVStatistics(path)
    }

    /**
     * Creates a PrintWriter with the supplied path and writes the full statistics
     * to the file. Full means all detailed statistical quantities for every statistic.
     *
     * @param pathToFile the path to the file
     * @return the PrintWriter so that additional information can be written to the file
     */
    fun writeFullAcrossReplicationStatistics(pathToFile: Path): PrintWriter {
        val out = KSLFileUtil.createPrintWriter(pathToFile)
        writeFullAcrossReplicationStatistics(out)
        return out
    }

    /**
     * Creates a PrintWriter with the supplied name in the default output directory for the model
     * writes out the across replication statistics.
     *
     * Full means all statistical quantities are printed for every statistic
     *
     * @param fName the file name
     * @return the PrintWriter so that additional information can be written to the file
     */
    fun writeFullAcrossReplicationStatistics(fName: String = model.simulationName + "_FullAcrossRepStatistics.txt"): PrintWriter {
        val path = model.outputDirectory.outDir.resolve(fName)
        return writeFullAcrossReplicationStatistics(path)
    }

    /**
     * Writes the across replication statistics as text values to System.out
     *
     */
    fun printFullAcrossReplicationStatistics() {
        writeFullAcrossReplicationStatistics(PrintWriter(System.out, true))
    }

    /**
     * Writes the across replication statistics as text values to System.out
     *
     */
    fun printAcrossReplicationSummaryStatistics() {
        writeAcrossReplicationSummaryStatistics(PrintWriter(System.out, true))
    }

    /**
     * Creates a PrintWriter with the supplied name in directory within
     * jslOutput and writes out the across replication statistics
     *
     * @param pathToFile the path to the file
     * @return the PrintWriter
     */
    fun writeAcrossReplicationSummaryStatistics(pathToFile: Path): PrintWriter {
        val out = KSLFileUtil.createPrintWriter(pathToFile)
        writeAcrossReplicationSummaryStatistics(out)
        return out
    }

    /**
     * Creates a PrintWriter with the supplied name in directory jslOutput and
     * writes out the across replication statistics
     *
     * @param fName the file name
     * @return the PrintWriter
     */
    fun writeAcrossReplicationSummaryStatistics(fName: String = model.simulationName + "_SummaryStatistics.txt"): PrintWriter {
        val path = model.outputDirectory.outDir.resolve(fName)
        return writeAcrossReplicationSummaryStatistics(path)
    }

//    /**
//     * Attaches the CSVReplicationReport to the model if not attached.
//     * If you turn on the reporting, you need to do it before running the simulation.
//     *
//     */
//    fun turnOnReplicationCSVStatisticReporting() {
//        if (!this::myCSVRepReport.isInitialized){
//            myCSVRepReport = CSVReplicationReport(model)
//        }
//        if (!model.isModelElementObserverAttached(myCSVRepReport)){
//            model.attachModelElementObserver(myCSVRepReport)
//        }
//    }
//
//    /**
//     * Detaches the CSVReplicationReport from the model.
//     *
//     */
//    fun turnOffReplicationCSVStatisticReporting() {
//        if (!this::myCSVRepReport.isInitialized){
//            return
//        }
//        model.detachModelElementObserver(myCSVRepReport)
//    }

    /**
     * Writes shortened across replication statistics to the supplied
     * PrintWriter as text output in LaTeX document form
     *
     * Response Name Average Std. Dev.
     *
     * @param out the PrintWriter to write to
     */
    fun writeAcrossReplicationSummaryStatisticsAsLaTeX(out: PrintWriter) {
        out.print(acrossReplicationStatisticsAsLaTeXDocument())
        out.flush()
    }

    /**
     * Creates a PrintWriter using the supplied path and writes out the across replication
     * statistics as a LaTeX file
     *
     * @param pathToFile the path to the file
     * @return the PrintWriter
     */
    fun writeAcrossReplicationSummaryStatisticsAsLaTeX(pathToFile: Path): PrintWriter {
        var pf = pathToFile
        if (!KSLFileUtil.isTeXFile(pf)) {
            // add tex
            pf = Path.of("$pf.tex")
        }
        val out = KSLFileUtil.createPrintWriter(pf)
        writeAcrossReplicationSummaryStatisticsAsLaTeX(out)
        return out
    }

    /**
     * Creates a file with the supplied name
     * in the simulation's output directory with the results as a LaTeX table.
     *
     * @param fName the file name
     * @return the PrintWriter
     */
    fun writeAcrossReplicationSummaryStatisticsAsLaTeX(fName: String): PrintWriter {
        // get the output directory for the simulation
        val pathToFile = model.outputDirectory.outDir.resolve(fName)
        return writeAcrossReplicationSummaryStatisticsAsLaTeX(pathToFile)
    }

    /**
     * Creates a file with name getSimulation().getName() + "_LaTeX_Across_Replication_Summary.tex"
     * in the simulation's output directory with the results as a LaTeX table.
     *
     * @return the PrintWriter
     */
    fun writeAcrossReplicationSummaryStatisticsAsLaTeX(): PrintWriter {
        val fName = model.simulationName + "_LaTeX_Across_Replication_Summary.tex"
        return writeAcrossReplicationSummaryStatisticsAsLaTeX(fName)
    }

    /**
     * List of StringBuilder representing LaTeX tables
     *
     * @param maxRows the maximum number of rows
     * @return the tables as StringBuilders
     */
    fun acrossReplicationStatisticsAsLaTeXTables(maxRows: Int = 60): List<StringBuilder> {
        val list = acrossReplicationStatisticsAsLaTeXTabular(maxRows)
        val caption = """
             \caption{Across Replication Statistics for ${model.simulationName}} 
             
             """.trimIndent()
        val captionc = """\caption{Across Replication Statistics for ${model.simulationName} (Continued)} 
"""
        val beginTable = "\\begin{table}[ht] \n"
        val endTable = "\\end{table} \n"
        val centering = "\\centering \n"
        val nReps = """
 Number of Replications ${experiment.currentReplicationNumber} 
"""
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
            //sb.append(" \n \\\\");
            sb.append(nReps)
            sb.append(endTable)
        }
        return list
    }

    /**
     * Returns a StringBuilder representation of the across replication
     * statistics as a LaTeX document
     *
     * @param maxRows maximum number of rows in each table
     * @return the StringBuilder
     */
    fun acrossReplicationStatisticsAsLaTeXDocument(maxRows: Int = 60): StringBuilder {
        val docClass = "\\documentclass[11pt]{article} \n"
        val beginDoc = "\\begin{document} \n"
        val endDoc = "\\end{document} \n"
        val list = acrossReplicationStatisticsAsLaTeXTables(maxRows)
        val sb = StringBuilder()
        sb.append(docClass)
        sb.append(beginDoc)
        for (s in list) {
            sb.append(s)
        }
        sb.append(endDoc)
        return sb
    }

    /**
     * Gets shortened across replication statistics for response variables as a
     * LaTeX tabular. Each StringBuilder in the list represents a tabular with a
     * maximum number of rows
     *
     * Response Name Average Std. Dev.
     *
     * @param maxRows maximum number of rows in each tabular
     * @return a List of StringBuilders
     */
    fun acrossReplicationStatisticsAsLaTeXTabular(maxRows: Int = 60): List<StringBuilder> {
        val builders: MutableList<StringBuilder> = ArrayList()
        val stats = model.listOfAcrossReplicationStatistics
        if (!stats.isEmpty()) {
            val hline = "\\hline"
            val f1 = "%s %n"
            val f2 = "%s & %12f & %12f \\\\ %n"
            val header = "Response Name & $ \\bar{x} $ & $ s $ \\\\"
            val beginTabular = "\\begin{tabular}{lcc}"
            val endTabular = "\\end{tabular}"
            var sb = StringBuilder()
            var f = Formatter(sb)
            f.format(f1, beginTabular)
            f.format(f1, hline)
            f.format(f1, header)
            f.format(f1, hline)
            var i = 0
            var mheaders = stats.size / maxRows
            if (stats.size % maxRows > 0) {
                mheaders = mheaders + 1
            }
            var nheaders = 1
            var inprogress = false
            for (stat in stats) {
                val avg = stat.average
                val std = stat.standardDeviation
                val name = stat.name
                f.format(f2, name, avg, std)
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

//    /**
//     * Attaches the CSVExperimentReport to the model if not attached.
//     *
//     */
//    fun turnOnAcrossReplicationStatisticReporting(){
//        if (!this::myCSVExpReport.isInitialized){
//            myCSVExpReport = CSVExperimentReport(model)
//        }
//        if (!model.isModelElementObserverAttached(myCSVExpReport)){
//            model.attachModelElementObserver(myCSVExpReport)
//        }
//    }
//
//    /**
//     * Detaches the CSVExperimentReport from the model.
//     *
//     */
//    fun turnOffAcrossReplicationStatisticReporting() {
//        if (!this::myCSVExpReport.isInitialized){
//            return
//        }
//        model.detachModelElementObserver(myCSVExpReport)
//    }

    /**
     *
     * @return a StatisticReporter holding the across replication statistics for reporting
     */
    fun acrossReplicationStatisticReporter(): StatisticReporter {
        val list = acrossReplicationStatisticsList().toMutableList()
        return StatisticReporter(list)
    }

    /**
     * @param title     the title
     * @param confLevel the confidence level
     * @return a StringBuilder representation of the half-width summary report
     */
    fun halfWidthSummaryReport(title: String? = null, confLevel: Double = 0.95): StringBuilder {
        val list = acrossReplicationStatisticsList().toMutableList()
        val sr = StatisticReporter(list)
        return sr.halfWidthSummaryReport(title, confLevel)
    }

    /**
     * @param title     the title of the report
     * @param confLevel the confidence level of the report
     */
    fun printHalfWidthSummaryReport(title: String? = null, confLevel: Double = 0.95) {
        writeHalfWidthSummaryReport(PrintWriter(System.out), title, confLevel)
    }

    /**
     * @param out       the place to write to
     * @param title     the title of the report
     * @param confLevel the confidence level of the report
     */
    fun writeHalfWidthSummaryReport(
        out: PrintWriter = PrintWriter(System.out),
        title: String? = null,
        confLevel: Double = 0.95
    ) {
        out.print(halfWidthSummaryReport(title, confLevel).toString())
        out.flush()
    }

    /**
     * @param out       the place to write to
     * @param title     the title of the report
     * @param confLevel the confidence level of the report
     */
    fun writeHalfWidthSummaryReportAsMarkDown(
        out: PrintWriter = PrintWriter(System.out),
        title: String? = null,
        confLevel: Double = 0.95,
        df: DecimalFormat? = null
    ) {
        val list = acrossReplicationStatisticsList().toMutableList()
        val sr = StatisticReporter(list)
        out.print(sr.halfWidthSummaryReportAsMarkDown(title, confLevel, df))
        out.flush()
    }
}