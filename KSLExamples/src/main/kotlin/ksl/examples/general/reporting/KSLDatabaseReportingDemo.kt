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
package ksl.examples.general.reporting

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.*
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.MultipleComparisonAnalyzer

// ── Helper — build and populate a KSLDatabase ────────────────────────────────

/**
 * Runs a Drive-Through Pharmacy simulation and returns the populated [KSLDatabase].
 *
 * The model includes a histogram on system time (`HistogramResponse`) and an integer
 * frequency response for number in queue on arrival, so all report sections are exercised.
 *
 * @param expName the experiment name to use; defaults to `"Pharmacy Experiment"`
 * @param numReps number of replications to run; defaults to 30
 */
private fun buildPharmacyDatabase(
    expName: String = "Pharmacy Experiment",
    numReps: Int = 30
): KSLDatabase {
    val myModel = Model("Drive-Through Pharmacy", autoCSVReports = false)
    myModel.experimentName = expName
    myModel.numberOfReplications = numReps
    myModel.lengthOfReplication = 480.0    // 8-hour shift
    myModel.lengthOfReplicationWarmUp = 60.0
    DriveThroughPharmacyWithQ(myModel)

    // Attach a SQLite database observer — the database file is written to kslOutput/db/
    val myObserver = KSLDatabaseObserver(myModel)

    myModel.simulate()
    myModel.print()

    return myObserver.db
}

// ── Demo 1: Single-experiment zero-code path ──────────────────────────────────

/**
 * Demonstrates the one-liner path for generating a simulation output report from a
 * [KSLDatabase] for a single named experiment.
 *
 * Produces:
 * - An experiment-configuration data table
 * - An across-replication statistics half-width summary table
 * - A histogram section for system time (bins + bar-chart plot)
 * - A frequency section for number in queue on arrival (table + bar-chart plot)
 *
 * The report is opened in the default browser and written as both Markdown and
 * plain text to `kslOutput/`.
 */
fun demoDbSingleExperimentReport() {
    val myDb = buildPharmacyDatabase(expName = "Pharmacy Experiment")

    // Zero-code: one call produces the complete report for the named experiment
    val myDoc = myDb.toReport("Pharmacy Experiment")
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    myDoc.writeText()
    println("Single-experiment DB report written to kslOutput/")
}

// ── Demo 2: All-experiments report ───────────────────────────────────────────

/**
 * Demonstrates the zero-code all-experiments path that produces a single
 * [ksl.utilities.io.report.ast.ReportNode.Document] covering every experiment
 * stored in the database.
 *
 * Two configurations ("Fast Service" and "Slow Service") are run and stored in
 * the same database.  [KSLDatabase.toReport] without an experiment name iterates
 * all experiments automatically, wrapping each in its own top-level section.
 */
fun demoDbAllExperimentsReport() {
    // Run two experiments (different service rates) into the same database
    val myModel = Model("Drive-Through Pharmacy", autoCSVReports = false)
    myModel.numberOfReplications = 20
    myModel.lengthOfReplication = 480.0
    myModel.lengthOfReplicationWarmUp = 60.0
    val myDtp = DriveThroughPharmacyWithQ(myModel)
    val myObserver = KSLDatabaseObserver(myModel)

    // ── Experiment A — default service rate (mean 0.5 minutes) ───────────────
    myModel.experimentName = "Fast Service"
    myDtp.serviceRV.initialRandomSource = ExponentialRV(0.5, 2)
    myModel.simulate()

    // ── Experiment B — slower service (mean 0.7 minutes) ─────────────────────
    myModel.experimentName = "Slow Service"
    myDtp.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)
    myModel.simulate()

    val myDb = myObserver.db
    println("Experiments in database: ${myDb.experimentNames}")

    // All-experiments report: one document, one section per experiment
    val myDoc = myDb.toReport(title = "Drive-Through Pharmacy — All Experiments")
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("All-experiments DB report written to kslOutput/")
}

// ── Demo 3: Custom composite report with MCA ─────────────────────────────────

/**
 * Demonstrates building a composite document that combines:
 * - Per-experiment simulation output sections (via [dbSimulationResults])
 * - A multiple comparison analysis section (via [multipleComparison]) using
 *   per-replication data extracted from the database
 *
 * This pattern is the database equivalent of the live-simulation composite report
 * shown in [demoCompositeReport].
 *
 * The MCA compares the mean system time across two server configurations.  Because
 * a lower system time is preferred, [MCBDirection.MIN] is specified.
 */
fun demoDbCompositeWithMcaReport() {
    // Run two experiments (fast vs slow service) and store both in the same DB
    val myModel = Model("Drive-Through Pharmacy", autoCSVReports = false)
    myModel.numberOfReplications = 15
    myModel.lengthOfReplication = 480.0
    myModel.lengthOfReplicationWarmUp = 60.0
    val myDtp = DriveThroughPharmacyWithQ(myModel)
    val myObserver = KSLDatabaseObserver(myModel)

    myModel.experimentName = "Fast Service"
    myDtp.serviceRV.initialRandomSource = ExponentialRV(0.5, 2)
    myModel.simulate()

    myModel.experimentName = "Slow Service"
    myDtp.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)
    myModel.simulate()

    val myDb = myObserver.db

    // Extract per-replication data for the MCA — compare System Time across configs
    val allRepData = myDb.replicationDataArraysByExperimentAndResponse()
    val mcaData = mutableMapOf<String, DoubleArray>()
    for (expName in myDb.experimentNames) {
        val repData = allRepData[expName] ?: continue
        // "System Time" is the exact name used by DriveThroughPharmacyWithQ
        val values = repData["System Time"]?.filter { !it.isNaN() }?.toDoubleArray()
        if (values != null && values.isNotEmpty()) {
            mcaData[expName] = values
        }
    }

    val myDoc = report("Drive-Through Pharmacy — Database Report with MCA") {
        paragraph(
            "This composite report covers two server configurations stored in the database, " +
            "followed by a multiple comparison analysis of mean system time. " +
            "Lower system time is preferred."
        )

        // ── Per-experiment sections ───────────────────────────────────────────
        for (expName in myDb.experimentNames) {
            section(expName) {
                dbSimulationResults(myDb, expName)
            }
        }

        // ── MCA section (only if we have data for at least two configs) ───────
        if (mcaData.size >= 2) {
            val myMca = MultipleComparisonAnalyzer(mcaData, "System Time (minutes)")
            section("Multiple Comparison Analysis — System Time") {
                paragraph(
                    "Comparing mean system time across ${mcaData.size} configurations " +
                    "using the MCB method.  A lower mean system time is preferred."
                )
                multipleComparison(
                    mca                = myMca,
                    altConfidenceLevel = 0.95,
                    showAltCIPlot      = true,
                    showBoxPlot        = true,
                    direction          = MCBDirection.MIN
                )
            }
        }
    }

    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Composite DB + MCA report written to kslOutput/")
}

// ── Demo 4: Custom DSL block — extend the default experiment report ───────────

/**
 * Demonstrates supplying a custom `block` to [KSLDatabase.toReport] to extend
 * the default experiment report with an additional commentary section.
 *
 * The custom block calls [dbSimulationResults] explicitly to include the standard
 * sections before appending custom content.  The [KSLDatabase] instance is captured
 * directly from the enclosing scope rather than via `this@toReport`, which is the
 * recommended pattern for call-site blocks.
 */
fun demoDbCustomBlockReport() {
    val myDb = buildPharmacyDatabase(expName = "Pharmacy Custom Report")
    val expName = "Pharmacy Custom Report"

    val myDoc = myDb.toReport(expName, title = "Pharmacy Analysis — Custom Report") {
        // Include the standard DB simulation output sections
        dbSimulationResults(myDb, expName)

        // Append a custom commentary section after the standard output
        section("Analyst Commentary") {
            paragraph(
                "This experiment used a warm-up period of 60 minutes to remove " +
                "the initial transient before collecting statistics. " +
                "The 95% half-width on system time indicates the results are stable " +
                "across 30 replications."
            )
        }
    }
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Custom-block DB report written to kslOutput/")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    // Run each demo in sequence. Each opens a browser tab and writes files
    // to kslOutput/. Comment out any demos you don't want to run.
    demoDbSingleExperimentReport()
//    demoDbAllExperimentsReport()
//    demoDbCompositeWithMcaReport()
//    demoDbCustomBlockReport()
}
