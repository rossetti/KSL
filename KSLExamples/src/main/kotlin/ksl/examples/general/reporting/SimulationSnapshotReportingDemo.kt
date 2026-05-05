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

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.app.session.RunEvent
import ksl.app.session.RunRequest
import ksl.app.session.RunResult
import ksl.app.session.Runner
import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.examples.general.variables.TestTimeSeriesResponse
import ksl.simulation.Model
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.report.extensions.snapshotSimulationResults
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Demonstrates reporting from [SimulationSnapshot.ExperimentCompleted], the immutable
 * in-memory result returned by [RunResult.Completed] when a model is executed through
 * the interaction-layer [Runner].
 *
 * Three demos:
 * 1. [demoSnapshotReportFromRunner] - zero-code snapshot report from a completed run
 * 2. [demoSnapshotCustomBlockReport] - custom DSL composition around the snapshot report
 * 3. [demoSnapshotTimeSeriesReport] - snapshot report with time-series rows enabled
 */
fun main() {
    demoSnapshotReportFromRunner()
    demoSnapshotCustomBlockReport()
    demoSnapshotTimeSeriesReport()
}

// -- Demo 1: Runner result snapshot -------------------------------------------

/**
 * Runs a Drive-Through Pharmacy model through [Runner], extracts the completed
 * snapshot from [RunResult.Completed], and renders it via the zero-code [toReport]
 * path.
 *
 * This is the intended application workflow for a GUI or TUI:
 * submit work to the interaction layer, observe events for progress, then report
 * the immutable completed snapshot without retaining the live model.
 */
fun demoSnapshotReportFromRunner() {
    println("=== Demo 1: Snapshot Report From Runner ===")

    val myModel = buildPharmacyModel(
        modelName = "Snapshot_Pharmacy",
        experimentName = "Snapshot Pharmacy Baseline",
        numReps = 30
    )

    val mySnapshot = runForSnapshot(myModel, observeProgress = true)

    val myDoc = mySnapshot.toReport(
        title = "Drive-Through Pharmacy - Snapshot Report",
        outputDirectory = myModel.outputDirectory
    )
    myDoc.showInBrowser()
    myDoc.writeMarkdown()

    println("Snapshot report written to ${myModel.outputDirectory.outDir}")
}

// -- Demo 2: Custom block ------------------------------------------------------

/**
 * Demonstrates using the snapshot report functions inside a custom DSL block.
 *
 * The standard snapshot results are included explicitly with plots disabled and
 * diagnostic fields enabled, then a short application-facing note is appended.
 */
fun demoSnapshotCustomBlockReport() {
    println("=== Demo 2: Snapshot Custom Block Report ===")

    val myModel = buildPharmacyModel(
        modelName = "Snapshot_Custom_Pharmacy",
        experimentName = "Snapshot Pharmacy Custom Report",
        numReps = 20
    )

    val mySnapshot = runForSnapshot(myModel)

    val myDoc = mySnapshot.toReport(
        title = "Drive-Through Pharmacy - Snapshot Custom Report",
        outputDirectory = myModel.outputDirectory
    ) {
        snapshotSimulationResults(
            mySnapshot,
            showPlots = false,
            showDiagnostics = true
        )
        section("Interaction Layer Notes") {
            paragraph(
                "This report was generated from RunResult.Completed.snapshot. " +
                "The live model is no longer needed after the runner returns the completed result."
            )
        }
    }

    myDoc.showInBrowser()
    myDoc.writeMarkdown()

    println("Custom snapshot report written to ${myModel.outputDirectory.outDir}")
}

// -- Demo 3: Time-series snapshot --------------------------------------------

/**
 * Demonstrates snapshot reporting when the model emits [ksl.modeling.variable.TimeSeriesResponse]
 * rows. The report reconstructs period-level across-replication summaries from the
 * immutable snapshot rows.
 */
fun demoSnapshotTimeSeriesReport() {
    println("=== Demo 3: Snapshot Time-Series Report ===")

    val myModel = buildTimeSeriesModel()
    val mySnapshot = runForSnapshot(myModel, observeProgress = true)

    val myDoc = mySnapshot.toReport(
        title = "Pharmacy Time-Series - Snapshot Report",
        showPlots = false,
        showTimeSeries = true,
        outputDirectory = myModel.outputDirectory
    )

    myDoc.showInBrowser()
    myDoc.writeMarkdown()

    println("Time-series snapshot report written to ${myModel.outputDirectory.outDir}")
}

// -- Helpers ------------------------------------------------------------------

private fun buildPharmacyModel(
    modelName: String,
    experimentName: String,
    numReps: Int
): Model {
    val myModel = Model(modelName, autoCSVReports = false)
    myModel.experimentName = experimentName
    myModel.numberOfReplications = numReps
    myModel.lengthOfReplication = 480.0
    myModel.lengthOfReplicationWarmUp = 60.0
    DriveThroughPharmacyWithQ(myModel)
    return myModel
}

private fun buildTimeSeriesModel(): Model {
    val myModel = Model("Snapshot_TimeSeries_Pharmacy", autoCSVReports = false)
    myModel.experimentName = "Snapshot Time-Series Pharmacy"
    myModel.numberOfReplications = 8
    myModel.lengthOfReplication = 500.0

    val myPharmacy = TestTimeSeriesResponse(myModel, 1, name = "Pharmacy")
    myPharmacy.arrivalGenerator.initialTimeBtwEvents = ExponentialRV(6.0, 1)
    myPharmacy.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    myPharmacy.timeSeriesResponse.acrossRepStatisticsOption = true

    return myModel
}

private fun runForSnapshot(
    model: Model,
    observeProgress: Boolean = false
): SimulationSnapshot.ExperimentCompleted = runBlocking {
    val myHandle = Runner().submit(RunRequest.SingleRun(model), this)

    val myProgressJob = if (observeProgress) {
        launch {
            myHandle.events.collect { event ->
                when (event) {
                    is RunEvent.ReplicationEnded -> {
                        println("Replication ${event.repNumber} of ${event.totalReplications} completed")
                    }
                    is RunEvent.RunCompleted -> println("Run completed")
                    is RunEvent.RunFailed -> println("Run failed: ${event.error}")
                    is RunEvent.RunCancelled -> println("Run cancelled: ${event.reason}")
                    else -> Unit
                }
            }
        }
    } else {
        null
    }

    val myResult = myHandle.result.await()
    myProgressJob?.cancelAndJoin()

    when (myResult) {
        is RunResult.Completed -> myResult.snapshot
        is RunResult.Cancelled -> error("Snapshot demo run was cancelled: ${myResult.reason}")
        is RunResult.Failed -> error("Snapshot demo run failed: ${myResult.error}")
        is RunResult.OrchestratorCompleted -> error("Snapshot demo expected a single-run result.")
    }
}
