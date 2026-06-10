/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.bookbundle

import ksl.app.bundle.KSLAppKind
import ksl.app.bundle.KSLBundledModel
import ksl.app.bundle.KSLModelBundle
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * The "KSL Book Examples" bundle: a curated, student-facing collection of
 * decision-relevant simulation models drawn from the KSL book (chapters 4
 * through 8).  Each model is adapted (copied, never the originals) into the
 * `ksl.examples.general.bookbundle` package and given an authored
 * `ModelCatalog` of its headline inputs and outputs via `curateCatalog`, so
 * the KSL apps surface a short, labeled decision surface instead of the full
 * auto-registered control/response set.
 *
 * Distinct from the dogfood bundles (`MM1Bundle`, `LKInventoryBundle`,
 * `SimoptTestModelsBundle`), which are test fixtures; this is curated content
 * for teaching and student use.  Registered for `ServiceLoader` discovery via
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` in this module.
 *
 * Models are added one book chapter at a time; see the chapter sections below.
 */
class BookExamplesBundle : KSLModelBundle {

    companion object {
        /** Stable, globally unique id of this bundle. */
        const val BUNDLE_ID: String = "edu.uark.ksl.book-examples"

        // ── Chapter 4 model ids ──
        const val DRIVE_THROUGH_PHARMACY_RESOURCE: String = "DriveThroughPharmacyWithResource"
        const val DRIVE_THROUGH_PHARMACY_QUEUE: String = "DriveThroughPharmacyWithQ"
        const val TANDEM_QUEUE: String = "TandemQueue"
    }

    override val bundleId: String = BUNDLE_ID

    override val displayName: String = "KSL Book Examples"

    override val description: String =
        "Curated, decision-relevant simulation models from the KSL book (chapters 4 " +
            "through 8), each with an authored catalog of headline inputs and outputs, " +
            "ready to run in the KSL apps."

    override val version: String = "1.0.0"

    override val kslApiVersion: String = "1.2"

    override val models: List<KSLBundledModel> = listOf(
        // Chapter 4
        DriveThroughPharmacyWithResourceModel,
        DriveThroughPharmacyWithQModel,
        TandemQueueModel,
    )

    // ════════════════════════════════ Chapter 4 ════════════════════════════════

    /**
     * Multi-server drive-through pharmacy modeled with an `SResource`.  Staffing
     * (pharmacist capacity) and mean service time are the decision inputs.
     */
    private object DriveThroughPharmacyWithResourceModel : KSLBundledModel {

        override val modelId: String = DRIVE_THROUGH_PHARMACY_RESOURCE

        override val displayName: String = "Drive-Through Pharmacy (Resource)"

        override val description: String =
            "Multi-server M/M/c drive-through pharmacy modeled with an SResource; " +
                "staffing and mean service time are the decision inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                // Child element name ("Pharmacy") must differ from the Model name.
                val model = Model(modelId, autoCSVReports = false)
                val sim = DriveThroughPharmacyWithResource(model, numServers = 1, name = "Pharmacy")
                model.numberOfReplications = 30
                model.lengthOfReplication = 20000.0
                model.lengthOfReplicationWarmUp = 5000.0
                model.curateCatalog {
                    // The pharmacist count is the SResource's initialCapacity control.
                    input("Pharmacy:Pharmacists.initialCapacity") {
                        displayName = "Number of Pharmacists"; unit = "pharmacists"
                    }
                    rvParameter(sim.serviceRV, "mean") { displayName = "Mean Service Time"; unit = "min" }
                    output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
                    output(sim.numInSystem) { displayName = "Avg Number in System" }
                    output(sim.probSystemTimeGT4Minutes) { displayName = "P(System Time >= 4 min)" }
                    output(sim.numCustomersServed) { displayName = "Number Served" }
                }
                return model
            }
        }
    }

    /**
     * The same drive-through pharmacy modeled with an explicit queue and a
     * `@KSLControl`-annotated `numPharmacists` server count.
     */
    private object DriveThroughPharmacyWithQModel : KSLBundledModel {

        override val modelId: String = DRIVE_THROUGH_PHARMACY_QUEUE

        override val displayName: String = "Drive-Through Pharmacy (Queue)"

        override val description: String =
            "Multi-server drive-through pharmacy modeled with an explicit queue; " +
                "the @KSLControl numPharmacists count and mean service time are the inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model(modelId, autoCSVReports = false)
                val sim = DriveThroughPharmacyWithQ(model, numServers = 1, name = "Pharmacy")
                model.numberOfReplications = 30
                model.lengthOfReplication = 20000.0
                model.lengthOfReplicationWarmUp = 5000.0
                model.curateCatalog {
                    input(sim, DriveThroughPharmacyWithQ::numPharmacists) {
                        displayName = "Number of Pharmacists"; unit = "pharmacists"
                    }
                    rvParameter(sim.serviceRV, "mean") { displayName = "Mean Service Time"; unit = "min" }
                    output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
                    output(sim.numInSystem) { displayName = "Avg Number in System" }
                    output(sim.probSystemTimeGT4Minutes) { displayName = "P(System Time >= 4 min)" }
                    output(sim.numCustomersServed) { displayName = "Number Served" }
                }
                return model
            }
        }
    }

    /**
     * Two stations in series (M/M/1 → M/M/1).  The two stations' mean service
     * times are nominated as decision inputs; system time and throughput are the
     * headline outputs.
     */
    private object TandemQueueModel : KSLBundledModel {

        override val modelId: String = TANDEM_QUEUE

        override val displayName: String = "Tandem Queue"

        override val description: String =
            "Two single-server stations in series; the per-station mean service " +
                "times are the decision inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT
        )

        override fun builder(): ModelBuilderIfc = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                // Child element name ("Tandem") must differ from the Model name.
                val model = Model(modelId, autoCSVReports = false)
                val sim = TandemQueue(model, name = "Tandem")
                model.numberOfReplications = 30
                model.lengthOfReplication = 20000.0
                model.lengthOfReplicationWarmUp = 5000.0
                model.curateCatalog {
                    rvParameter(sim.station1.activityTimeRV, "mean") {
                        displayName = "Station 1 Mean Service Time"; unit = "min"
                    }
                    rvParameter(sim.station2.activityTimeRV, "mean") {
                        displayName = "Station 2 Mean Service Time"; unit = "min"
                    }
                    output(sim.numInSystem) { displayName = "Avg Number in System" }
                    output(sim.totalSystemTime) { displayName = "Avg Total Time in System"; unit = "min" }
                    output(sim.totalProcessed) { displayName = "Number Processed" }
                }
                return model
            }
        }
    }
}
