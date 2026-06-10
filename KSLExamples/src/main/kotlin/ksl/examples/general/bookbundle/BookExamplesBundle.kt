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

        // ── Chapter 5 model ids ──
        const val PALLET_WORK_CENTER: String = "PalletWorkCenter"

        // ── Chapter 6 model ids ──
        const val STEM_FAIR_MIXER: String = "StemFairMixer"
        const val TIE_DYE_TSHIRTS: String = "TieDyeTShirts"

        // ── Chapter 7 model ids ──
        const val WALK_IN_HEALTH_CLINIC: String = "WalkInHealthClinic"
        const val STEM_FAIR_MIXER_ENHANCED: String = "StemFairMixerEnhanced"
        const val STEM_FAIR_MIXER_ENHANCED_SCHED: String = "StemFairMixerEnhancedSched"
        const val RQ_INVENTORY_SYSTEM: String = "RQInventorySystem"
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
        // Chapter 5
        PalletWorkCenterModel,
        // Chapter 6
        StemFairMixerModel,
        TieDyeTShirtsModel,
        // Chapter 7
        WalkInHealthClinicModel,
        StemFairMixerEnhancedModel,
        StemFairMixerEnhancedSchedModel,
        RQInventorySystemModel,
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

    // ════════════════════════════════ Chapter 5 ════════════════════════════════

    /**
     * A pallet work center staffed by a controllable number of workers.  Each
     * replication is a terminating "day" that processes a random number of
     * pallets; the staffing level and mean transport time are the decision
     * inputs, with worker utilization and the probability of overtime (a day
     * exceeding 480 minutes) as the headline outputs.
     */
    private object PalletWorkCenterModel : KSLBundledModel {

        override val modelId: String = PALLET_WORK_CENTER

        override val displayName: String = "Pallet Work Center"

        override val description: String =
            "Terminating pallet-processing work center; the worker count (staffing " +
                "decision) and mean transport time are the inputs, with utilization and " +
                "P(overtime) as headline outputs."

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
                // Child element name ("PWC") must differ from the Model name.
                // Terminating simulation: set only the replication count (each
                // replication ends when the day's pallets are processed).
                val model = Model(modelId, autoCSVReports = false)
                val sim = PalletWorkCenter(model, numWorkers = 2, name = "PWC")
                model.numberOfReplications = 30
                model.curateCatalog {
                    input(sim, PalletWorkCenter::numWorkers) {
                        displayName = "Number of Workers"; unit = "workers"
                    }
                    rvParameter(sim.transportTimeRV, "mean") {
                        displayName = "Mean Transport Time"; unit = "min"
                    }
                    output(sim.workerUtilization) { displayName = "Worker Utilization" }
                    output(sim.probOfOverTime) { displayName = "P(Overtime > 480 min)" }
                    output(sim.totalProcessingTime) { displayName = "Total Processing Time"; unit = "min" }
                    output(sim.numInSystem) { displayName = "Avg Pallets at Work Center" }
                    output(sim.numPalletsProcessed) { displayName = "Pallets Processed" }
                }
                return model
            }
        }
    }

    // ════════════════════════════════ Chapter 6 ════════════════════════════════

    /**
     * The STEM career-fair mixer (process-view): students arrive, optionally
     * wander, and may talk with two recruiter teams.  The two recruiter-team
     * capacities are the decision inputs; overall and by-type time-in-system
     * are the headline outputs.  The mixer runs for a fixed 6-hour horizon.
     */
    private object StemFairMixerModel : KSLBundledModel {

        override val modelId: String = STEM_FAIR_MIXER

        override val displayName: String = "STEM Fair Mixer (basic)"

        override val description: String =
            "Process-view STEM career-fair mixer; the JH-Bunt and Mal-Wart recruiter " +
                "capacities are the decision inputs, with overall and by-type student " +
                "time-in-system as outputs."

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
                // Child element name ("StemFair") must differ from the Model name.
                val model = Model(modelId, autoCSVReports = false)
                val sim = StemFairMixer(model, name = "StemFair")
                model.numberOfReplications = 400
                model.lengthOfReplication = 6.0 * 60.0   // a single 6-hour mixer
                model.curateCatalog {
                    // Recruiter-team capacities (ResourceWithQ initialCapacity controls).
                    input("JHBuntR.initialCapacity") {
                        displayName = "JH-Bunt Recruiters"; unit = "recruiters"
                    }
                    input("MalWartR.initialCapacity") {
                        displayName = "Mal-Wart Recruiters"; unit = "recruiters"
                    }
                    output("OverallSystemTime") { displayName = "Avg Time in System"; unit = "min" }
                    output("NumInSystem") { displayName = "Avg Number in System" }
                    output("NonWanderSystemTime") { displayName = "Avg Time in System (non-wanderers)"; unit = "min" }
                    output("WanderSystemTime") { displayName = "Avg Time in System (wanderers)"; unit = "min" }
                    output("LeaverSystemTime") { displayName = "Avg Time in System (leavers)"; unit = "min" }
                }
                return model
            }
        }
    }

    /**
     * A tie-dye T-shirt shop (process-view with a blocking queue): orders spawn
     * shirts that are made in parallel, then collected and packaged.  The
     * shirt-maker and packager capacities are the decision inputs; order
     * time-in-system and number in system are the outputs.
     */
    private object TieDyeTShirtsModel : KSLBundledModel {

        override val modelId: String = TIE_DYE_TSHIRTS

        override val displayName: String = "Tie-Dye T-Shirts"

        override val description: String =
            "Process-view tie-dye T-shirt shop using a blocking queue to coordinate " +
                "shirt-making and packaging; the shirt-maker and packager capacities " +
                "are the decision inputs."

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
                // Child element name ("TieDye") must differ from the Model name.
                val model = Model(modelId, autoCSVReports = false)
                val sim = TieDyeTShirts(model, name = "TieDye")
                model.numberOfReplications = 30
                model.lengthOfReplication = 480.0
                model.curateCatalog {
                    input("ShirtMakers_R.initialCapacity") {
                        displayName = "Shirt Makers"; unit = "workers"
                    }
                    input("Packager_R.initialCapacity") {
                        displayName = "Packagers"; unit = "workers"
                    }
                    output("System Time") { displayName = "Avg Order Time in System"; unit = "min" }
                    output("Num in System") { displayName = "Avg Number of Orders in System" }
                }
                return model
            }
        }
    }

    // ════════════════════════════════ Chapter 7 ════════════════════════════════

    /**
     * A walk-in health clinic with triage, priority (ranked) doctor queue,
     * balking by low-priority patients, and reneging.  Doctor and triage-nurse
     * capacities plus the balk threshold are the decision inputs; time-in-system
     * (overall and by priority) and the balk/renege probabilities are outputs.
     */
    private object WalkInHealthClinicModel : KSLBundledModel {

        override val modelId: String = WALK_IN_HEALTH_CLINIC

        override val displayName: String = "Walk-In Health Clinic"

        override val description: String =
            "Walk-in clinic with triage, a ranked doctor queue, balking, and reneging; " +
                "doctor and triage capacities and the balk threshold are the inputs."

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
                // Child element name ("WalkInClinic") must differ from the Model name.
                val model = Model(modelId, autoCSVReports = false)
                val sim = WalkInHealthClinic(model, name = "WalkInClinic")
                model.numberOfReplications = 30
                model.lengthOfReplication = 10.0 * 60.0   // a 10-hour clinic day
                model.curateCatalog {
                    input("Doctors.initialCapacity") { displayName = "Number of Doctors"; unit = "doctors" }
                    input("TriageNurse.initialCapacity") { displayName = "Number of Triage Nurses"; unit = "nurses" }
                    input(sim, WalkInHealthClinic::balkCriteria) {
                        displayName = "Balk Threshold (queue length)"; unit = "patients"
                    }
                    output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
                    output("WalkInClinic:TimeInSystemHigh") { displayName = "Avg Time in System (high priority)"; unit = "min" }
                    output("WalkInClinic:TimeInSystemMedium") { displayName = "Avg Time in System (medium priority)"; unit = "min" }
                    output("WalkInClinic:TimeInSystemLow") { displayName = "Avg Time in System (low priority)"; unit = "min" }
                    output(sim.probBalking) { displayName = "P(Balk)" }
                    output(sim.probReneging) { displayName = "P(Renege)" }
                    output("WalkInClinic:NumServed") { displayName = "Number Served" }
                    output("WalkInClinic:NumBalked") { displayName = "Number Balked" }
                    output("WalkInClinic:NumReneged") { displayName = "Number Reneged" }
                }
                return model
            }
        }
    }

    /**
     * The enhanced STEM mixer: NHPP arrivals over a 6-hour horizon, walking
     * times, conversation area, and a closing-time rush.  The two recruiter-team
     * capacities are the (static) decision inputs.
     */
    private object StemFairMixerEnhancedModel : KSLBundledModel {

        override val modelId: String = STEM_FAIR_MIXER_ENHANCED

        override val displayName: String = "STEM Fair Mixer (enhanced)"

        override val description: String =
            "Enhanced STEM mixer with non-stationary arrivals, walking times, and a " +
                "closing rush; the JH-Bunt and Mal-Wart recruiter capacities are the inputs."

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
                // Child element name ("StemFairEnhanced") must differ from the Model name.
                // Terminating: arrivals stop when the mixer closes, then students finish.
                val model = Model(modelId, autoCSVReports = false)
                val sim = StemFairMixerEnhanced(model, name = "StemFairEnhanced")
                model.numberOfReplications = 400
                model.curateCatalog {
                    input("JHBuntR.initialCapacity") { displayName = "JH-Bunt Recruiters"; unit = "recruiters" }
                    input("MalWartR.initialCapacity") { displayName = "Mal-Wart Recruiters"; unit = "recruiters" }
                    output("OverallSystemTime") { displayName = "Avg Time in System"; unit = "min" }
                    output("RecruitingOnlySystemTime") { displayName = "Avg Time in System (recruiting only)"; unit = "min" }
                    output("MixingStudentSystemTime") { displayName = "Avg Time in System (mixers)"; unit = "min" }
                    output("NumInSystem") { displayName = "Avg Number in System" }
                    output("NumInSystemAtClosing") { displayName = "Number in System at Closing" }
                    output("TotalNumberArrivals") { displayName = "Total Arrivals" }
                    output("Mixer Ending Time") { displayName = "Mixer Ending Time"; unit = "min" }
                }
                return model
            }
        }
    }

    /**
     * The scheduled-capacity variant of the enhanced mixer: recruiter capacities
     * follow hourly schedules and a time-series response records staffing over
     * the evening.  Because the capacities are schedule-driven, the mixer length
     * and warning time are this variant's decision inputs.
     */
    private object StemFairMixerEnhancedSchedModel : KSLBundledModel {

        override val modelId: String = STEM_FAIR_MIXER_ENHANCED_SCHED

        override val displayName: String = "STEM Fair Mixer (enhanced + schedule)"

        override val description: String =
            "Enhanced STEM mixer with hourly recruiter-capacity schedules and a " +
                "time-series response; the mixer length and warning time are the inputs."

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
                // Child element name ("StemFairScheduled") must differ from the Model name.
                val model = Model(modelId, autoCSVReports = false)
                val sim = StemFairMixerEnhancedSched(model, name = "StemFairScheduled")
                model.numberOfReplications = 400
                model.curateCatalog {
                    input(sim, StemFairMixerEnhancedSched::lengthOfMixer) {
                        displayName = "Mixer Length"; unit = "min"
                    }
                    input(sim, StemFairMixerEnhancedSched::warningTime) {
                        displayName = "Warning Time Before Close"; unit = "min"
                    }
                    output("OverallSystemTime") { displayName = "Avg Time in System"; unit = "min" }
                    output("RecruitingOnlySystemTime") { displayName = "Avg Time in System (recruiting only)"; unit = "min" }
                    output("MixingStudentSystemTime") { displayName = "Avg Time in System (mixers)"; unit = "min" }
                    output("NumInSystem") { displayName = "Avg Number in System" }
                    output("NumInSystemAtClosing") { displayName = "Number in System at Closing" }
                    output("Mixer Ending Time") { displayName = "Mixer Ending Time"; unit = "min" }
                }
                return model
            }
        }
    }

    /**
     * A single-stage (R, Q) inventory system with constant demand and lead time.
     * The reorder point (R) and reorder quantity (Q) are the decision variables;
     * total cost, fill rate, ordering frequency, and inventory levels are the
     * outputs.  This is the curriculum (R, Q) optimization example.
     */
    private object RQInventorySystemModel : KSLBundledModel {

        override val modelId: String = RQ_INVENTORY_SYSTEM

        override val displayName: String = "(R, Q) Inventory System"

        override val description: String =
            "Single-stage (R, Q) inventory model; reorder point (R) and reorder " +
                "quantity (Q) are the decision variables, with total cost, fill rate, " +
                "and inventory levels as outputs."

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
                // Child element name ("RQInventory") must differ from the Model name;
                // the (R,Q) controls/responses live on its inner "RQInventory:Item".
                val model = Model(modelId, autoCSVReports = false)
                RQInventorySystem(model, reorderPt = 1, reorderQty = 2, name = "RQInventory")
                model.lengthOfReplication = 20000.0
                model.lengthOfReplicationWarmUp = 10000.0
                model.numberOfReplications = 40
                model.curateCatalog {
                    input("RQInventory:Item.initialReorderPoint") { displayName = "Reorder Point (R)"; unit = "units" }
                    input("RQInventory:Item.initialReorderQty") { displayName = "Reorder Quantity (Q)"; unit = "units" }
                    output("RQInventory:Item:TotalCost") { displayName = "Avg Total Cost"; unit = "\$/period" }
                    output("RQInventory:Item:FillRate") { displayName = "Fill Rate" }
                    output("RQInventory:Item:OrderingFrequency") { displayName = "Ordering Frequency" }
                    output("RQInventory:Item:OnHand") { displayName = "Avg On-Hand Inventory"; unit = "units" }
                    output("RQInventory:Item:AmountBackOrdered") { displayName = "Avg Backorders"; unit = "units" }
                }
                return model
            }
        }
    }
}
