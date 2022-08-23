/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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
import java.util.*

/**
 * This class facilitates simulation output reporting. There are two main
 * reporting functions: within replication statistics and across replication
 * statistics.
 *
 * To collect within replication statistics you must use
 * turnOnReplicationCSVStatisticalReporting() before running the simulation.
 * This needs to be done before the simulation is run because the statistics are
 * collected after each replication is completed. This method attaches a
 * CSVReplicationReport to the model for collection purposes. If the simulation
 * is run multiple times, then statistical data continues to be observed by the
 * CSVReplicationReport. Thus, data across many experiments can be captured in
 * this manner. This produces a comma separated value file containing all end of
 * replication statistical summaries for every counter and response variable in
 * the model.
 *
 * There are a number of options available if you want to capture across
 * replication statistics.
 *
 * 1) turnOnAcrossReplicationCSVStatisticReporting() - This should be done
 * before running the simulation. It uses a CSVExperimentReport to observe a
 * model. This produces a comma separated value file containing all across
 * replication statistical summaries for every counter and response variable in
 * the model.
 *
 * 2) Use any of the writeAcrossReplicationX() methods. These methods
 * will write across replication summary statistics to files, standard output,
 * LaTeX, CSV, etc.
 *
 * @author rossetti
 */
class SimulationReporter(theModel: Model) {

    private val model: Model = theModel
    private val experiment: ExperimentIfc = theModel.myExperiment
    private val myCSVRepReport: CSVReplicationReport = CSVReplicationReport(theModel)
    private val myCSVExpReport: CSVExperimentReport = CSVExperimentReport(theModel)

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
    val acrossReplicationCSVStatistics: StringBuilder
        get() {
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
            val stat = r.acrossReplicationStatistic
            if (r.defaultReportingOption) {
                out.println(stat)
            }
        }
        for (c in counters) {
            val stat = c.acrossReplicationStatistic
            if (c.defaultReportingOption) {
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
     * Returns a StringBuilder with across replication statistics
     *
     * @return the StringBuilder
     */
    val acrossReplicationStatistics: StringBuilder
        get() {
            val sb = StringBuilder()
            getAcrossReplicationStatistics(sb)
            return sb
        }

    /**
     * Gets the across replication statistics as a list
     *
     * @return a list filled with the across replication statistics
     */
    val acrossReplicationStatisticsList: List<StatisticIfc>
        get() {
            val list: MutableList<StatisticIfc> = ArrayList()
            fillAcrossReplicationStatistics(list)
            return list
        }

    /** Fills the supplied list with the across replication statistics
     *
     * @param list the list to fill
     */
    fun fillAcrossReplicationStatistics(list: MutableList<StatisticIfc>) {
        fillResponseVariableReplicationStatistics(list)
        fillCounterAcrossReplicationStatistics(list)
    }

    /** Fills the list with across replication statistics from the response
     * variables (ResponseVariable and TimeWeighted).
     *
     * @param list the list to fill
     */
    fun fillResponseVariableReplicationStatistics(list: MutableList<StatisticIfc>) {
        for (r in responses) {
            val stat = r.acrossReplicationStatistic
            if (r.defaultReportingOption) {
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
            val stat = c.acrossReplicationStatistic
            if (c.defaultReportingOption) {
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
    fun getAcrossReplicationStatistics(sb: StringBuilder) {
        sb.append(Date())
        sb.append(System.lineSeparator())
        sb.append("Simulation Results for Model:")
        sb.append(System.lineSeparator())
        sb.append(model.name)
        sb.append(System.lineSeparator())
        sb.append(this)
        sb.append(System.lineSeparator())
        for (r in responses) {
            val stat = r.acrossReplicationStatistic
            if (r.defaultReportingOption) {
                sb.append(stat)
                sb.appendLine()
            }
        }
        for (c in counters) {
            val stat = c.acrossReplicationStatistic
            if (c.defaultReportingOption) {
                sb.append(stat)
                sb.appendLine()
            }
        }
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
        Objects.requireNonNull(pathToFile, "The path to the file was null")
        val out = KSLFileUtil.createPrintWriter(pathToFile)
        writeAcrossReplicationCSVStatistics(out)
        return out
    }

    /**
     * Creates a PrintWriter with the supplied name in directory jslOutput and
     * writes out the across replication statistics
     *
     * @param fName the file name
     * @return the PrintWriter
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
     * @return the PrintWriter
     */
    fun writeFullAcrossReplicationStatistics(pathToFile: Path): PrintWriter {
        val out = KSLFileUtil.createPrintWriter(pathToFile)
        writeFullAcrossReplicationStatistics(out)
        return out
    }

    /**
     * Creates a PrintWriter with the supplied name in directory jslOutput and
     * writes out the across replication statistics.
     *
     * Full means all statistical quantities are printed for every statistic
     *
     * @param fName the file name
     * @return the PrintWriter
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
    /**
     * Attaches a CSVReplicationReport to the model to record within replication
     * statistics to a file
     *
     * @param name the report file name
     */
    /**
     * Attaches a CSVReplicationReport to the model to record within replication
     * statistics to a file
     *
     */
    @JvmOverloads
    fun turnOnReplicationCSVStatisticReporting(name: String? = null) {
        var name = name
        if (myCSVRepReport != null) {
            model.deleteObserver(myCSVRepReport)
        }
        if (name == null) {
            name = simulation.name + "_ReplicationReport.csv"
        }
        // path inside jslOutput with simulation's name on it
        val dirPath = simulation.outputDirectory.outDir
        // now need path to csv replication report results
        val filePath = dirPath.resolve(name)
        myCSVRepReport = CSVReplicationReport(filePath)
        model.addObserver(myCSVRepReport)
    }

    /**
     * Detaches a CSVReplicationReport from the model
     *
     */
    fun turnOffReplicationCSVStatisticReporting() {
        if (myCSVRepReport != null) {
            model.deleteObserver(myCSVRepReport)
        }
    }

    /**
     * Writes shortened across replication statistics to the supplied
     * PrintWriter as text output in LaTeX document form
     *
     * Response Name Average Std. Dev.
     *
     * @param out the PrintWriter to write to
     */
    fun writeAcrossReplicationSummaryStatisticsAsLaTeX(out: PrintWriter) {
        out.print(acrossReplicationStatisticsAsLaTeXDocument)
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
     * List of StringBuilder representing LaTeX tables max 60 rows
     *
     * @return the tables as StringBuilders
     */
    val acrossReplicationStatisticsAsLaTeXTables: List<StringBuilder>
        get() = getAcrossReplicationStatisticsAsLaTeXTables(60)

    /**
     * List of StringBuilder representing LaTeX tables
     *
     * @param maxRows the maximum number of rows
     * @return the tables as StringBuilders
     */
    fun getAcrossReplicationStatisticsAsLaTeXTables(maxRows: Int): List<StringBuilder> {
        val list = getAcrossReplicationStatisticsAsLaTeXTabular(maxRows)
        val hline = "\\hline"
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
     * statistics as a LaTeX document with max number of rows = 60
     *
     * @return the tables as StringBuilders
     */
    val acrossReplicationStatisticsAsLaTeXDocument: StringBuilder
        get() = getAcrossReplicationStatisticsAsLaTeXDocument(60)

    /**
     * Returns a StringBuilder representation of the across replication
     * statistics as a LaTeX document
     *
     * @param maxRows maximum number of rows in each table
     * @return the StringBuilder
     */
    fun getAcrossReplicationStatisticsAsLaTeXDocument(maxRows: Int): StringBuilder {
        val docClass = "\\documentclass[11pt]{article} \n"
        val beginDoc = "\\begin{document} \n"
        val endDoc = "\\end{document} \n"
        val list = getAcrossReplicationStatisticsAsLaTeXTables(maxRows)
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
     * maximum number of 60 rows
     *
     * Response Name Average Std. Dev.
     *
     * @return a List of StringBuilders
     */
    val acrossReplicationStatisticsAsLaTeXTabular: List<StringBuilder>
        get() = getAcrossReplicationStatisticsAsLaTeXTabular(60)

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
    fun getAcrossReplicationStatisticsAsLaTeXTabular(maxRows: Int): List<StringBuilder> {
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
    /**
     * Attaches a CSVExperimentReport to the model to record across replication
     * statistics to a file
     *
     * @param name the file name of the report
     */
    /**
     * Attaches a CSVExperimentReport to the model to record across replication
     * statistics to a file
     *
     */
    @JvmOverloads
    fun turnOnAcrossReplicationCSVStatisticReporting(name: String? = null) {
        var name = name
        if (myCSVExpReport != null) {
            model.deleteObserver(myCSVExpReport)
        }
        if (name == null) {
            name = simulation.name + "_CSVExperimentReport.csv"
        }
        // path inside jslOutput with simulation's name on it
        val dirPath = simulation.outputDirectory.outDir
        // now need path to csv replication report results
        val filePath = dirPath.resolve(name)
        myCSVExpReport = CSVExperimentReport(filePath)
        model.addObserver(myCSVExpReport)
    }

    /**
     * Detaches a CSVExperimentReport from the model
     *
     */
    fun turnOffAcrossReplicationStatisticReporting() {
        if (myCSVExpReport != null) {
            model.deleteObserver(myCSVExpReport)
        }
    }

    /**
     *
     * @return a StatisticReporter holding the across replication statistics for reporting
     */
    val acrossReplicationStatisticReporter: StatisticReporter
        get() {
            val list = acrossReplicationStatisticsList
            return StatisticReporter(list)
        }
    //    /**
    //     *  Prints a half-width summary report for the across replication statistics to
    //     *  System.out
    //     */
    //    public final void printAcrossReplicationHalfWidthSummaryReport(){
    //        PrintWriter printWriter = new PrintWriter(System.out);
    //        writeAcrossReplicationHalfWidthSummaryReport(printWriter);
    //    }
    //
    //    /** Writes a half-width summary report for the across replication statistics
    //     *
    //     * @param out the writer to write to
    //     */
    //    public final void writeAcrossReplicationHalfWidthSummaryReport(PrintWriter out){
    //        List<StatisticAccessorIfc> list = getAcrossReplicationStatisticsList();
    //        StatisticReporter statisticReporter = new StatisticReporter(list);
    //        StringBuilder report = statisticReporter.getHalfWidthSummaryReport();
    //        out.println(report.toString());
    //        out.flush();
    //    }
    /**
     * @return a StringBuilder with the Half-Width Summary Report and 95 percent confidence
     */
    val halfWidthSummaryReport: StringBuilder
        get() = getHalfWidthSummaryReport(null, 0.95)

    /**
     * @param confLevel the confidence level of the report
     * @return a StringBuilder with the Half-Width Summary Report
     */
    fun getHalfWidthSummaryReport(confLevel: Double): StringBuilder {
        return getHalfWidthSummaryReport(null, confLevel)
    }

    /**
     * @param title     the title
     * @param confLevel the confidence level
     * @return a StringBuilder representation of the half-width summary report
     */
    fun getHalfWidthSummaryReport(title: String?, confLevel: Double): StringBuilder {
        val list = acrossReplicationStatisticsList
        val sr = StatisticReporter(list)
        return sr.halfWidthSummaryReport(title, confLevel)
    }

    /**
     * Prints the default half-width summary report to the console
     */
    fun printHalfWidthSummaryReport() {
        writeHalfWidthSummaryReport(PrintWriter(System.out), null, 0.95)
    }

    /**
     * @param confLevel the confidence level of the report
     */
    fun printHalfWidthSummaryReport(confLevel: Double) {
        writeHalfWidthSummaryReport(PrintWriter(System.out), null, confLevel)
    }

    /**
     * @param title     the title of the report
     * @param confLevel the confidence level of the report
     */
    fun printHalfWidthSummaryReport(title: String?, confLevel: Double) {
        writeHalfWidthSummaryReport(PrintWriter(System.out), title, confLevel)
    }

    /**
     * @param out       the place to write to
     * @param confLevel the confidence level of the report
     */
    fun writeHalfWidthSummaryReport(out: PrintWriter?, confLevel: Double) {
        writeHalfWidthSummaryReport(out, null, confLevel)
    }
    /**
     * @param out       the place to write to
     * @param title     the title of the report
     * @param confLevel the confidence level of the report
     */
    /**
     * @param out the place to write to
     */
    @JvmOverloads
    fun writeHalfWidthSummaryReport(out: PrintWriter?, title: String? = null, confLevel: Double = 0.95) {
        requireNotNull(out) { "The PrintWriter was null" }
        out.print(getHalfWidthSummaryReport(title, confLevel).toString())
        out.flush()
    }
}