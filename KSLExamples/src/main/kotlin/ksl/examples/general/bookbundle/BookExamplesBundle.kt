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

        // ── Chapter 8 model ids ──
        const val TEST_AND_REPAIR_RESOURCE_CONSTRAINED: String = "TestAndRepairShopResourceConstrained"
        const val TANDEM_QUEUE_CONSTRAINED_MOVEMENT: String = "TandemQueueWithConstrainedMovement"
        const val TANDEM_QUEUE_UNCONSTRAINED_MOVEMENT: String = "TandemQueueWithUnconstrainedMovement"
        const val TEST_AND_REPAIR_MOVABLE_RESOURCES: String = "TestAndRepairShopWithMovableResources"
        const val TEST_AND_REPAIR_CONVEYOR: String = "TestAndRepairShopWithConveyor"

        // ── Capstone (general inventory optimization) ──
        const val TWO_ECHELON_INVENTORY: String = "TwoEchelonInventory"
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
        // Chapter 8
        TestAndRepairShopResourceConstrainedModel,
        TandemQueueWithConstrainedMovementModel,
        TandemQueueWithUnconstrainedMovementModel,
        TestAndRepairShopWithMovableResourcesModel,
        TestAndRepairShopWithConveyorModel,
        // Capstone
        TwoEchelonInventoryModel,
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

        override fun builder(): ModelBuilderIfc = DriveThroughPharmacyWithResourceModelBuilder()
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

        override fun builder(): ModelBuilderIfc = DriveThroughPharmacyWithQModelBuilder()
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

        override fun builder(): ModelBuilderIfc = TandemQueueModelBuilder()
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

        override fun builder(): ModelBuilderIfc = PalletWorkCenterModelBuilder()
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

        override fun builder(): ModelBuilderIfc = StemFairMixerModelBuilder()
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

        override fun builder(): ModelBuilderIfc = TieDyeTShirtsModelBuilder()
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

        override fun builder(): ModelBuilderIfc = WalkInHealthClinicModelBuilder()
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

        override fun builder(): ModelBuilderIfc = StemFairMixerEnhancedModelBuilder()
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

        override fun builder(): ModelBuilderIfc = StemFairMixerEnhancedSchedModelBuilder()
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

        override fun builder(): ModelBuilderIfc = RQInventorySystemModelBuilder()
    }

    // ════════════════════════════════ Chapter 8 ════════════════════════════════
    //
    // Material-handling variants of the test-and-repair shop share a common
    // "ProbWithinLimit" (P of meeting the 480-minute contract) output, so the
    // three designs (resource-constrained, movable resources, conveyor) make a
    // natural Scenario comparison.

    /**
     * Test-and-repair job shop with worker pools constraining both processing
     * and transport.  Machine capacities are the decision inputs; the headline
     * output is the probability a job meets the 480-minute contract.
     */
    private object TestAndRepairShopResourceConstrainedModel : KSLBundledModel {

        override val modelId: String = TEST_AND_REPAIR_RESOURCE_CONSTRAINED

        override val displayName: String = "Test & Repair Shop (resource-constrained)"

        override val description: String =
            "Test-and-repair job shop where shared worker pools constrain processing " +
                "and transport; machine capacities are the inputs and P(within the " +
                "480-minute contract) is the headline output."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = TestAndRepairShopResourceConstrainedModelBuilder()
    }

    /**
     * Two-station tandem queue with constrained movement: dedicated movable
     * resources carry parts between stations.  Station worker counts and mean
     * service times are the inputs.
     */
    private object TandemQueueWithConstrainedMovementModel : KSLBundledModel {

        override val modelId: String = TANDEM_QUEUE_CONSTRAINED_MOVEMENT

        override val displayName: String = "Tandem Queue (constrained movement)"

        override val description: String =
            "Two-station tandem queue where movable resources (carts) carry parts " +
                "between stations; station worker counts and mean service times are inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT
        )

        override fun builder(): ModelBuilderIfc = TandemQueueWithConstrainedMovementModelBuilder()
    }

    /**
     * The same tandem queue with unconstrained movement: parts move themselves
     * between stations (no transport resource).  Station worker counts and mean
     * service times are the inputs.
     */
    private object TandemQueueWithUnconstrainedMovementModel : KSLBundledModel {

        override val modelId: String = TANDEM_QUEUE_UNCONSTRAINED_MOVEMENT

        override val displayName: String = "Tandem Queue (unconstrained movement)"

        override val description: String =
            "Two-station tandem queue where parts move themselves between stations " +
                "(no transport resource); station worker counts and mean service times are inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT
        )

        override fun builder(): ModelBuilderIfc = TandemQueueWithUnconstrainedMovementModelBuilder()
    }

    /**
     * Test-and-repair shop where a pool of movable resources transports parts
     * over a distance network between stations.  Worker and machine capacities
     * are the inputs; the 480-minute contract probability is the headline output.
     */
    private object TestAndRepairShopWithMovableResourcesModel : KSLBundledModel {

        override val modelId: String = TEST_AND_REPAIR_MOVABLE_RESOURCES

        override val displayName: String = "Test & Repair Shop (movable resources)"

        override val description: String =
            "Test-and-repair shop where a pool of movable resources transports parts " +
                "over a distance network; worker and machine capacities are the inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = TestAndRepairShopWithMovableResourcesModelBuilder()
    }

    /**
     * Test-and-repair shop where an accumulating loop conveyor moves parts
     * between stations.  Station capacities are the inputs; the 480-minute
     * contract probability is the headline output.
     */
    private object TestAndRepairShopWithConveyorModel : KSLBundledModel {

        override val modelId: String = TEST_AND_REPAIR_CONVEYOR

        override val displayName: String = "Test & Repair Shop (conveyor)"

        override val description: String =
            "Test-and-repair shop where an accumulating loop conveyor moves parts " +
                "between stations; station capacities are the inputs."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = TestAndRepairShopWithConveyorModelBuilder()
    }

    // ═══════════════════════════════ Capstone ═══════════════════════════════
    //
    // Not a book chapter: the bundle's flagship simulation-optimization model,
    // reused in place from the general inventory package rather than copied
    // (its constructor needs many random variables and costs, and the package
    // already ships ready-made optimization problem definitions).

    /**
     * Two-echelon (R, Q) inventory system: a distribution center feeding a base.
     * The four reorder-point/quantity decision variables and the cost / fill-rate
     * outputs are nominated here.  The general package's
     * constrained/unconstrainedTwoEchelonProblemDefinition() functions provide a
     * ready-made optimization problem over the same keys.
     */
    private object TwoEchelonInventoryModel : KSLBundledModel {

        override val modelId: String = TWO_ECHELON_INVENTORY

        override val displayName: String = "Two-Echelon (R, Q) Inventory"

        override val description: String =
            "Two-echelon (R, Q) inventory system (a distribution center feeding a base); " +
                "four reorder point/quantity decision variables with total cost and " +
                "fill-rate outputs.  Reused from BuildTwoEchelonModel; pairs with the " +
                "package's ready-made optimization problem definitions."

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = TwoEchelonInventoryModelBuilder()
    }
}
