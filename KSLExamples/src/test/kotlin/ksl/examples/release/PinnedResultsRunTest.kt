/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.release

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 *  Pins the results of every process-view example: each runnable `main` of the book's chapters 6 to 8 and
 *  of the other process-view examples, and each process-view model a builder or its class can build. A
 *  change to KSL that alters an example's results fails here, naming the example and the lines that moved.
 *
 *  Each example runs in a JVM of its own, so examples never share KSL's default stream provider, and with
 *  logging switched off, so the pinned output never depends on how logging is set up. A `main` is pinned by
 *  its printed output, normalized for dates, execution times, paths and object hashes. A model is pinned by
 *  every response and counter at full precision (see `PinnedModelRun`).
 *
 *  Opt-in, like the other example sweeps: `./gradlew :KSLExamples:test -Dksl.runExamples=true --tests
 *  "ksl.examples.release.PinnedResultsRunTest"`. It takes about four minutes, four examples at a time. With
 *  `-Dksl.updatePins=true` as well, it writes the pins instead of checking them; do that only when a
 *  change of results is intended and understood, and say why in the commit.
 */
class PinnedResultsRunTest {

    private companion object {
        val PER_EXAMPLE_TIMEOUT_SECONDS = 300L
        const val PARALLEL = 4

        /** Left out, each with its reason; never silently dropped. */
        val EXCLUDED: Map<String, String> = mapOf(
            "ksl.examples.general.models.SomeProcessTestingKt" to
                "runs DumpTruckSystemProcessView, which re-activates a completed process in its second " +
                "replication; KSL refuses that, so the example stops. The example is broken, not KSL",
            "ksl.examples.general.agv.AgvThroughputBenchmarkKt" to
                "a benchmark: it prints wall-clock speed, which differs on every run"
        )

        /** The runnable process-view `main`s, less the exclusions. */
        val MAINS: List<String> = listOf(
            "ksl.examples.book.chapter6.Ch6BatchQueueKt",
            "ksl.examples.book.chapter6.Ch6BlockUntilAllCompletedKt",
            "ksl.examples.book.chapter6.Ch6BlockUntilCompletedKt",
            "ksl.examples.book.chapter6.Ch6Example1Kt",
            "ksl.examples.book.chapter6.Ch6Example2Kt",
            "ksl.examples.book.chapter6.Ch6Example3Kt",
            "ksl.examples.book.chapter6.Ch6Example4Kt",
            "ksl.examples.book.chapter6.Ch6Example5Kt",
            "ksl.examples.book.chapter6.Ch6Example6Kt",
            "ksl.examples.book.chapter6.Ch6Example7Kt",
            "ksl.examples.book.chapter6.Ch6Example8Kt",
            "ksl.examples.book.chapter6.Ch6Example9Kt",
            "ksl.examples.book.chapter6.SoccerMomV3Kt",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaBlockageKt",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaBlockingActivityKt",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaHoldQKt",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaRawSuspendKt",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaSignalKt",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaSyncWithKt",
            "ksl.examples.book.chapter6.interaction.SoccerMomWithSuspensionsKt",
            "ksl.examples.book.chapter7.Ch7Example1Kt",
            "ksl.examples.book.chapter7.Ch7Example2Kt",
            "ksl.examples.book.chapter7.Ch7Example3Kt",
            "ksl.examples.book.chapter7.Ch7Example4Kt",
            "ksl.examples.book.chapter7.Ch7Example5Kt",
            "ksl.examples.book.chapter7.Ch7Example6Kt",
            "ksl.examples.book.chapter7.Ch7Example7Kt",
            "ksl.examples.book.chapter7.Ch7Example8Kt",
            "ksl.examples.book.chapter7.Ch7Example9Kt",
            "ksl.examples.book.chapter8.Ch8Example1Kt",
            "ksl.examples.book.chapter8.Ch8Example2Kt",
            "ksl.examples.book.chapter8.Ch8Example3Kt",
            "ksl.examples.book.chapter8.Ch8Example4Kt",
            "ksl.examples.book.chapter8.Ch8Example5Kt",
            "ksl.examples.book.chapter8.Ch8Example6Kt",
            "ksl.examples.book.chapter8.Ch8Section8_4_2_1Kt",
            "ksl.examples.book.chapter8.Ch8Section8_4_2_2Kt",
            "ksl.examples.book.chapter8.Ch8Section8_4_2_3Kt",
            "ksl.examples.book.chapter8.Ch8Section8_4_3Kt",
            "ksl.examples.book.chapter8.Ch8Section8_4_4Kt",
            "ksl.examples.book.chapter8.TandemQueueWithConveyorsViaDelayKt",
            "ksl.examples.general.agent.AgentArrivalsExampleKt",
            "ksl.examples.general.agent.AgentPerformanceExampleKt",
            "ksl.examples.general.agent.AutonomousDeliveryExampleKt",
            "ksl.examples.general.agent.AutonomousForkliftExampleKt",
            "ksl.examples.general.agent.BuildingEvacuationExampleKt",
            "ksl.examples.general.agent.CorridorPedestrianExampleKt",
            "ksl.examples.general.agent.DroneDeliveryExampleKt",
            "ksl.examples.general.agent.FlockingExampleKt",
            "ksl.examples.general.agent.GridEpidemicExampleKt",
            "ksl.examples.general.agent.JobShopExampleKt",
            "ksl.examples.general.agent.NetworkRumorExampleKt",
            "ksl.examples.general.agent.PedestrianCrowdExampleKt",
            "ksl.examples.general.agent.WarehouseAGVExampleKt",
            "ksl.examples.general.agv.DispatchingRuleComparisonKt",
            "ksl.examples.general.agv.MultiFloorHospitalExampleKt",
            "ksl.examples.general.agv.RetaskingInFlightExampleKt",
            "ksl.examples.general.agv.TwoLaneWarehouseExampleKt",
            "ksl.examples.general.agv.TwoParadigmsExampleKt",
            "ksl.examples.general.fleet.FreePathFleetExampleKt",
            "ksl.examples.general.guidedpath.CrossingArbiterExampleKt",
            "ksl.examples.general.guidedpath.GuidePathDisturbancesExampleKt",
            "ksl.examples.general.guidedpath.SimpleAGVExampleKt",
            "ksl.examples.general.guidedpath.ZoneClosurePolicyExampleKt",
            "ksl.examples.general.lectures.week12.Day23Kt",
            "ksl.examples.general.lectures.week13.MfgSystemKt",
            "ksl.examples.general.lectures.week8.Day16bKt",
            "ksl.examples.general.lectures.week9.Drill6ActivityKt",
            "ksl.examples.general.lectures.week9.LectureActivity1Kt",
            "ksl.examples.general.lectures.week9.LectureActivity1V2Kt",
            "ksl.examples.general.lectures.week9.LectureActivity2Kt",
            "ksl.examples.general.misc.EntityGeneratorTestKt",
            "ksl.examples.general.misc.SimpleProcessQKt",
            "ksl.examples.general.misc.TestBlockUntilAllCompletedKt",
            "ksl.examples.general.misc.TestBlockUntilCompletionKt",
            "ksl.examples.general.misc.TestHoldQKt",
            "ksl.examples.general.misc.TestProcessActivationKt",
            "ksl.examples.general.misc.TestProcessModelingKt",
            "ksl.examples.general.misc.TestResourceKt",
            "ksl.examples.general.misc.TestResourcePoolKt",
            "ksl.examples.general.misc.TestResourceScheduleKt",
            "ksl.examples.general.models.MfgSystemKt",
            "ksl.examples.general.models.ProposedModel2Kt",
            "ksl.examples.general.models.SeizeTestingKt",
            "ksl.examples.general.models.TestBatchQKt",
            "ksl.examples.general.models.TestDelayKt",
            "ksl.examples.general.models.YBoxSystemPVKt",
            "ksl.examples.general.models.conveyors.BanksEtAlConveyorExampleKt",
            "ksl.examples.general.models.conveyors.ConveyorExample1Kt",
            "ksl.examples.general.models.conveyors.ConveyorExample2Kt",
            "ksl.examples.general.models.conveyors.ConveyorExample3Kt",
            "ksl.examples.general.models.conveyors.ConveyorExample4Kt",
            "ksl.examples.general.models.conveyors.ConveyorExample5Kt",
            "ksl.examples.general.models.conveyors.ConveyorTest2Kt",
            "ksl.examples.general.models.conveyors.ConveyorTest3Kt",
            "ksl.examples.general.models.conveyors.ConveyorTestingKt",
            "ksl.examples.general.models.conveyors.TestAndRepairShopWithConveyorKt",
            "ksl.examples.general.models.conveyors.TestConveyorTransferKt",
            "ksl.examples.general.models.jobshop.JobShopKt",
            "ksl.examples.general.models.station.TieDyeStationKt",
            "ksl.examples.general.models.timesharedcomputer.TimeSharedComputerPVKt",
            "ksl.examples.general.models.wsc2025.ActiveResourceExampleBQKt",
            "ksl.examples.general.models.wsc2025.ActiveResourceExampleHQKt",
            "ksl.examples.general.models.wsc2025.ActiveResourceExampleSignalKt",
            "ksl.examples.general.models.wsc2025.SimpleServiceSystemKt",
            "ksl.examples.general.models.wsc2025.TestAndRepairSystemKt",
            "ksl.examples.general.processview.resources.ClinicBreaksKt",
            "ksl.examples.general.processview.resources.EmergencyDoctorsKt",
            "ksl.examples.general.processview.resources.MachineBreakdownsKt",
            "ksl.examples.general.processview.resources.ReroutingKt",
            "ksl.examples.general.processview.resources.RushOrdersKt",
            "ksl.examples.general.processview.resources.ShiftsAndBreakdownsKt",
            "ksl.examples.general.processview.waits.FirstFreeOfTwoKt",
            "ksl.examples.general.processview.waits.HospitalBedsKt",
            "ksl.examples.general.processview.waits.ImpatientCallersKt",
            "ksl.examples.general.processview.waits.KittingWithExpeditingKt",
            "ksl.examples.general.processview.waits.ShuttleStopKt",
            "ksl.examples.general.processview.waits.TimedAuctionKt",
            "ksl.examples.general.processview.waits.TwoColourBinKt",
            "ksl.examples.general.processview.waits.WalkInHealthClinicViaTimedSeizeKt",
            "ksl.examples.general.spatial.TransporterExampleKt"
        )

        /** The models their builders build. */
        val BUILDERS: List<String> = listOf(
            "ksl.examples.general.animationbundle.Example01DriveThroughPharmacyBuilder",
            "ksl.examples.general.animationbundle.Example02MovingPartsBuilder",
            "ksl.examples.general.animationbundle.Example03GridEpidemicBuilder",
            "ksl.examples.general.animationbundle.Example04BuildingEvacuationBuilder",
            "ksl.examples.general.animationbundle.Example05PedestrianCrowdBuilder",
            "ksl.examples.general.animationbundle.Example06WarehouseAGVBuilder",
            "ksl.examples.general.animationbundle.Example08ConveyorTandemBuilder",
            "ksl.examples.general.animationbundle.Example09DistancesTandemBuilder",
            "ksl.examples.general.animationbundle.Example11FlockingBuilder",
            "ksl.examples.general.animationbundle.Example12StemFairStorageBuilder",
            "ksl.examples.general.animationbundle.Example13MovableResourcesBuilder",
            "ksl.examples.general.animationbundle.Example14AnnotatedClinicBuilder",
            "ksl.examples.general.animationbundle.Example15DroneDeliveryBuilder",
            "ksl.examples.general.animationbundle.Example17TandemBlockingBuilder",
            "ksl.examples.general.animationbundle.Example18ConveyorTestRepairBuilder",
            "ksl.examples.general.bookbundle.DriveThroughPharmacyWithQModelBuilder",
            "ksl.examples.general.bookbundle.DriveThroughPharmacyWithResourceModelBuilder",
            "ksl.examples.general.bookbundle.PalletWorkCenterModelBuilder",
            "ksl.examples.general.bookbundle.RQInventorySystemModelBuilder",
            "ksl.examples.general.bookbundle.StemFairMixerEnhancedModelBuilder",
            "ksl.examples.general.bookbundle.StemFairMixerEnhancedSchedModelBuilder",
            "ksl.examples.general.bookbundle.StemFairMixerModelBuilder",
            "ksl.examples.general.bookbundle.TandemQueueModelBuilder",
            "ksl.examples.general.bookbundle.TandemQueueWithConstrainedMovementModelBuilder",
            "ksl.examples.general.bookbundle.TandemQueueWithUnconstrainedMovementModelBuilder",
            "ksl.examples.general.bookbundle.TestAndRepairShopResourceConstrainedModelBuilder",
            "ksl.examples.general.bookbundle.TestAndRepairShopWithConveyorModelBuilder",
            "ksl.examples.general.bookbundle.TestAndRepairShopWithMovableResourcesModelBuilder",
            "ksl.examples.general.bookbundle.TieDyeTShirtsModelBuilder",
            "ksl.examples.general.bookbundle.TwoEchelonInventoryModelBuilder",
            "ksl.examples.general.bookbundle.WalkInHealthClinicModelBuilder",
            "ksl.examples.general.vehiclebundle.ActiveFleetShopModelBuilder",
            "ksl.examples.general.vehiclebundle.DispatchingRulesRingShopModelBuilder",
            "ksl.examples.general.vehiclebundle.FreePathFleetYardModelBuilder",
            "ksl.examples.general.vehiclebundle.GuidePathDisturbancesModelBuilder",
            "ksl.examples.general.vehiclebundle.MultiFloorHospitalModelBuilder",
            "ksl.examples.general.vehiclebundle.PassiveTransporterShopModelBuilder",
            "ksl.examples.general.vehiclebundle.PedestrianCrossingModelBuilder",
            "ksl.examples.general.vehiclebundle.SimpleAgvShopModelBuilder",
            "ksl.examples.general.vehiclebundle.TestAndRepairShopWithGuidedTransportersModelBuilder",
            "ksl.examples.general.vehiclebundle.TwoLaneWarehouseModelBuilder"
        )

        /** The process-view models built from their class, with only a parent. */
        val CLASSES: List<String> = listOf(
            "ksl.examples.book.chapter6.BatchQueueExample",
            "ksl.examples.book.chapter6.BlockUntilAllCompletedExample",
            "ksl.examples.book.chapter6.BlockUntilCompletedExample",
            "ksl.examples.book.chapter6.BlockingQExample",
            "ksl.examples.book.chapter6.DriveThroughPharmacy",
            "ksl.examples.book.chapter6.EntityGeneratorExample",
            "ksl.examples.book.chapter6.HoldQExample",
            "ksl.examples.book.chapter6.SignalExample",
            "ksl.examples.book.chapter6.SoccerMomV3",
            "ksl.examples.book.chapter6.SoccerMomWithSuspensions",
            "ksl.examples.book.chapter6.StemFairMixer",
            "ksl.examples.book.chapter6.TieDyeTShirts",
            "ksl.examples.book.chapter6.WaitForProcessExample",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaBlockage",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaBlockageNoPrinting",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaBlockingActivity",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaHoldQ",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaRawSuspend",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaSignal",
            "ksl.examples.book.chapter6.interaction.SoccerMomViaSyncWith",
            "ksl.examples.book.chapter6.interaction.SoccerMomWithSuspensions",
            "ksl.examples.book.chapter7.ResourcePoolExample",
            "ksl.examples.book.chapter7.StemFairMixerEnhanced",
            "ksl.examples.book.chapter7.StemFairMixerEnhancedSched",
            "ksl.examples.book.chapter7.TandemQueue",
            "ksl.examples.book.chapter7.TandemQueueV2",
            "ksl.examples.book.chapter7.TandemQueueWithBlocking",
            "ksl.examples.book.chapter7.TestAndRepairShop",
            "ksl.examples.book.chapter7.WalkInHealthClinic",
            "ksl.examples.book.chapter8.ConveyorMerging",
            "ksl.examples.book.chapter8.StemFairMixerEnhancedWithMovement",
            "ksl.examples.book.chapter8.TandemQueueWithConstrainedMovement",
            "ksl.examples.book.chapter8.TandemQueueWithConstrainedMovementV2",
            "ksl.examples.book.chapter8.TandemQueueWithConstrainedMovementV3",
            "ksl.examples.book.chapter8.TandemQueueWithConveyors",
            "ksl.examples.book.chapter8.TandemQueueWithConveyorsViaDelay",
            "ksl.examples.book.chapter8.TandemQueueWithUnconstrainedMovement",
            "ksl.examples.book.chapter8.TandemQueueWithUnconstrainedMovementV2",
            "ksl.examples.book.chapter8.TandemQueueWithWorkOnConveyors",
            "ksl.examples.book.chapter8.TestAndRepairShopResourceConstrained",
            "ksl.examples.book.chapter8.TestAndRepairShopWithConveyor",
            "ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources",
            "ksl.examples.general.bookbundle.StemFairMixer",
            "ksl.examples.general.bookbundle.StemFairMixerEnhanced",
            "ksl.examples.general.bookbundle.StemFairMixerEnhancedSched",
            "ksl.examples.general.bookbundle.TandemQueueWithConstrainedMovement",
            "ksl.examples.general.bookbundle.TandemQueueWithUnconstrainedMovement",
            "ksl.examples.general.bookbundle.TestAndRepairShopResourceConstrained",
            "ksl.examples.general.bookbundle.TestAndRepairShopWithConveyor",
            "ksl.examples.general.bookbundle.TestAndRepairShopWithMovableResources",
            "ksl.examples.general.bookbundle.TieDyeTShirts",
            "ksl.examples.general.bookbundle.WalkInHealthClinic",
            "ksl.examples.general.lectures.week8.DTPWithQProcessView",
            "ksl.examples.general.lectures.week9.Airport",
            "ksl.examples.general.lectures.week9.MfgSystemActivity",
            "ksl.examples.general.lectures.week9.MfgSystemActivityV2",
            "ksl.examples.general.lectures.week9.ProductionSystemActivity",
            "ksl.examples.general.misc.EntityGeneratorTest",
            "ksl.examples.general.misc.SimpleProcessQ",
            "ksl.examples.general.misc.TestBlockUntilCompletion",
            "ksl.examples.general.misc.TestHoldQ",
            "ksl.examples.general.misc.TestProcessActivation",
            "ksl.examples.general.misc.TestProcessModeling",
            "ksl.examples.general.misc.TestResource",
            "ksl.examples.general.misc.TestResourcePool",
            "ksl.examples.general.misc.TestResourceSchedule",
            "ksl.examples.general.models.ClinicDesignA",
            "ksl.examples.general.models.ClinicDesignB",
            "ksl.examples.general.models.ProposedModelClass2",
            "ksl.examples.general.models.SeizeTesting",
            "ksl.examples.general.models.TestBatchQ",
            "ksl.examples.general.models.TestDelay",
            "ksl.examples.general.models.YBoxSystemPV",
            "ksl.examples.general.models.conveyors.BanksEtAlConveyorExample",
            "ksl.examples.general.models.conveyors.ConveyorExample1",
            "ksl.examples.general.models.conveyors.ConveyorExample2",
            "ksl.examples.general.models.conveyors.ConveyorExample3",
            "ksl.examples.general.models.conveyors.ConveyorExample4",
            "ksl.examples.general.models.conveyors.ConveyorExample5",
            "ksl.examples.general.models.conveyors.TestAndRepairShopWithConveyor",
            "ksl.examples.general.models.conveyors.TestConveyorTransfer",
            "ksl.examples.general.models.jobshop.JobShop",
            "ksl.examples.general.models.timesharedcomputer.TimeSharedComputerPV",
            "ksl.examples.general.models.wsc2025.MM1ViaActiveResourceViaBQ",
            "ksl.examples.general.models.wsc2025.MM1ViaActiveResourceViaHQ",
            "ksl.examples.general.models.wsc2025.MM1ViaActiveResourceViaSignal",
            "ksl.examples.general.models.wsc2025.SimpleServiceSystem",
            "ksl.examples.general.models.wsc2025.TestAndRepairSystem"
        )

        private val datePattern = Regex("""\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) +\d+ [\d:]+ \w+ \d{4}""")
        private val timeLine = Regex("""(Beginning|End|Elapsed) Execution Time|Execution Time:""", RegexOption.IGNORE_CASE)

        /**
         *  The printed output of a `main`, without what differs from run to run or machine to machine. The
         *  example's working directory, in [workPaths], is replaced first, whichever form of it is printed.
         */
        fun normalize(text: String, workPaths: List<String>): String = text.lineSequence()
            .filterNot { timeLine.containsMatchIn(it) }
            .map { line -> workPaths.fold(line) { acc, w -> acc.replace(w, "<work>") } }
            .map {
                it.replace(Regex("""\d{4}-\d\d-\d\dT[\d:.]+Z?"""), "<ts>")
                    .replace(datePattern, "<date>")
                    .replace(Regex("""(/private/var/folders|/private/tmp|/var/folders|/tmp|/Users|/home|[A-Z]:\\)\S*"""), "<path>")
                    .replace(Regex("""@[0-9a-f]{5,}"""), "@<hash>")
                    .replace(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""), "<uuid>")
                    .trimEnd()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n", postfix = "\n")

        val updating: Boolean get() = System.getProperty("ksl.updatePins") == "true"

        val pinRoot: File = File("src/test/resources/pinned")
    }

    private class Outcome(val name: String, val text: String?, val problem: String?)

    /** Runs [args] in a JVM of its own, in a fresh directory, and returns its exit, its output and the directory's names. */
    private fun runJvm(vararg args: String): Triple<Int, String, List<String>> {
        val work: Path = Files.createTempDirectory("pinned")
        // longest first, so the resolved form is replaced before the plain one it contains
        val names = listOf(work.toRealPath().toString(), work.toAbsolutePath().toString()).distinct().sortedByDescending { it.length }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val logConfig = File(pinRoot, "logback-silent.xml").absolutePath
        // an example run with the invariant checks on checks them in its own JVM too
        val invariants = System.getProperty("ksl.checkInvariants")?.let { listOf("-Dksl.checkInvariants=$it") } ?: emptyList()
        val command = listOf(java, "-Djava.awt.headless=true", "-Dlogback.configurationFile=$logConfig") + invariants +
            listOf("-cp", System.getProperty("java.class.path")) + args
        val output = work.resolve("stdout.txt").toFile()
        val process = ProcessBuilder(command).directory(work.toFile())
            .redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val finished = process.waitFor(PER_EXAMPLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val text = if (output.exists()) output.readText() else ""
        work.toFile().deleteRecursively()
        return Triple(if (finished) process.exitValue() else -1, text, names)
    }

    private fun runMain(cls: String): Outcome {
        val (exit, text, names) = runJvm(cls)
        return if (exit != 0) Outcome(cls, null, "exited with $exit") else Outcome(cls, normalize(text, names), null)
    }

    private fun runModel(kind: String, cls: String): Outcome {
        val outFile = Files.createTempFile("pinned-model", ".txt")
        val (exit, _, _) = runJvm("ksl.examples.release.PinnedModelRunKt", kind, cls, outFile.absolutePathString())
        val text = outFile.toFile().readText()
        Files.deleteIfExists(outFile)
        return if (exit != 0) Outcome(cls, null, "exited with $exit") else Outcome(cls, text, null)
    }

    private fun check(folder: String, outcome: Outcome) {
        if (outcome.problem != null) fail("${outcome.name} ${outcome.problem}")
        val pin = File(pinRoot, "$folder/${outcome.name}.txt")
        if (updating) {
            pin.parentFile.mkdirs()
            pin.writeText(outcome.text!!)
            return
        }
        if (!pin.exists()) fail("${outcome.name} has no pinned results; run with -Dksl.updatePins=true to make them")
        assertEquals(pin.readText(), outcome.text, "${outcome.name}: its results differ from the pinned ones")
    }

    @TestFactory
    @DisplayName("every process-view example gives its pinned results")
    fun pinnedResults(): List<DynamicTest> {
        assumeTrue(System.getProperty("ksl.runExamples") == "true", "set -Dksl.runExamples=true to run the example sweeps")
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            val jobs = MAINS.map { cls -> "mains" to pool.submit(Callable { runMain(cls) }) } +
                BUILDERS.map { cls -> "models" to pool.submit(Callable { runModel("builder", cls) }) } +
                CLASSES.map { cls -> "models" to pool.submit(Callable { runModel("class", cls) }) }
            return jobs.map { (folder, future) ->
                val outcome = future.get()
                DynamicTest.dynamicTest(outcome.name) { check(folder, outcome) }
            }
        } finally {
            pool.shutdown()
        }
    }
}
