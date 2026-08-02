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

package ksl.app.moda

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 *  Tests for running studies on behalf of a caller.
 *
 *  The property everything else rests on is that studies do not interfere. Each gets its own model,
 *  built inside its own run and never handed out except as a recorded result, which is what makes
 *  it safe to put behind a service without locking the engine. Most of what is checked here is that
 *  running many at once gives each the answer it would have got alone.
 */
class ModaSessionTest {

    private fun documentNamed(name: String, costs: Map<String, Double>): ModaDocument = ModaDocument(
        name = name,
        metrics = listOf(
            MetricSpec("Cost", weight = 2.0, upperLimit = 1000.0),
            MetricSpec("Delay", weight = 1.0, upperLimit = 1000.0)
        ),
        alternatives = costs.keys.toList(),
        source = ModaSourceReference.InlineScores(
            costs.mapValues { (_, cost) -> mapOf("Cost" to cost, "Delay" to 1000.0 - cost) }
        )
    )

    private fun standardDocument(offset: Double = 0.0): ModaDocument = documentNamed(
        "Study $offset",
        mapOf("North" to 100.0 + offset, "South" to 300.0 + offset, "East" to 500.0 + offset)
    )

    // ------------------------------------------------------------------------------------------
    // Running studies
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a submitted study runs and reports its recommendation")
    fun aSubmittedStudyRunsAndReportsItsRecommendation() {
        ModaSession().use { session ->
            val outcome = session.submitAndAwaitBlocking(standardDocument())
            val finished = assertIs<ModaStudyOutcome.Finished>(outcome, "the study did not finish: $outcome")
            val completed = assertIs<ModaRunResult.Completed>(finished.run)
            assertTrue(completed.snapshot.primaryRecommendation in completed.snapshot.alternatives)
            assertEquals(finished.studyId, outcome.studyId)
        }
    }

    @Test
    @DisplayName("a study says where it got to as it goes, and ends exactly once")
    fun aStudySaysWhereItGotToAsItGoesAndEndsExactlyOnce() {
        ModaSession().use { session ->
            val handle = session.submit(standardDocument())
            val outcome = handle.awaitResultBlocking()
            assertIs<ModaStudyOutcome.Finished>(outcome)

            val events = runBlocking {
                withTimeout(10_000) { handle.events.replayCache }
            }
            assertTrue(events.any { it is ModaEvent.Started }, "no start was reported: $events")
            assertTrue(events.any { it is ModaEvent.Checked }, "no check was reported: $events")
            assertTrue(events.any { it is ModaEvent.ScoresRead }, "no scores were reported: $events")
            val endings = events.count {
                it is ModaEvent.Completed || it is ModaEvent.Refused ||
                        it is ModaEvent.Failed || it is ModaEvent.Cancelled
            }
            assertEquals(1, endings, "a study must end exactly once: $events")
            assertTrue(events.all { it.studyId == handle.studyId }, "an event carried another study's id")
        }
    }

    @Test
    @DisplayName("a study that cannot be run finishes with the reasons rather than breaking")
    fun aStudyThatCannotBeRunFinishesWithTheReasonsRatherThanBreaking() {
        ModaSession().use { session ->
            val broken = standardDocument().copy(
                metrics = listOf(MetricSpec("Cost", valueFunctionId = "nope", upperLimit = 100.0))
            )
            val finished = assertIs<ModaStudyOutcome.Finished>(session.submitAndAwaitBlocking(broken))
            val invalid = assertIs<ModaRunResult.Invalid>(finished.run)
            assertTrue(invalid.errors.isNotEmpty())
            assertTrue(finished.completed == null, "a refused study should carry no result")
        }
    }

    @Test
    @DisplayName("every study gets its own identifier")
    fun everyStudyGetsItsOwnIdentifier() {
        ModaSession().use { session ->
            val ids = (1..20).map { session.submit(standardDocument(it.toDouble())).studyId }
            assertEquals(ids.size, ids.toSet().size, "two studies were given the same identifier")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Studies running at the same time do not interfere
    // ------------------------------------------------------------------------------------------

    /**
     *  The invariant the whole design rests on. Each study builds its own model inside its own run,
     *  so running many at once must give each exactly the answer it would have got alone.
     */
    @Test
    @DisplayName("studies running at the same time each get the answer they would have got alone")
    fun studiesRunningAtTheSameTimeEachGetTheAnswerTheyWouldHaveGotAlone() {
        val documents = (0 until 24).map { standardDocument(it * 10.0) }

        // What each study comes to when nothing else is happening.
        val alone = ModaSession().use { session ->
            documents.map { document ->
                assertIs<ModaStudyOutcome.Finished>(session.submitAndAwaitBlocking(document))
            }.map { assertIs<ModaRunResult.Completed>(it.run).snapshot }
        }

        // And what they come to when all submitted together.
        val together = ModaSession().use { session ->
            val handles = documents.map { session.submit(it) }
            handles.map { handle ->
                val finished = assertIs<ModaStudyOutcome.Finished>(handle.awaitResultBlocking())
                assertIs<ModaRunResult.Completed>(finished.run).snapshot
            }
        }

        assertEquals(alone.size, together.size)
        for ((index, expected) in alone.withIndex()) {
            assertEquals(
                expected, together[index],
                "study $index came out differently when run alongside others"
            )
        }
    }

    @Test
    @DisplayName("the same study submitted many times at once comes out the same every time")
    fun theSameStudySubmittedManyTimesAtOnceComesOutTheSameEveryTime() {
        ModaSession().use { session ->
            val handles = (1..24).map { session.submit(standardDocument()) }
            val snapshots = handles.map {
                assertIs<ModaRunResult.Completed>(
                    assertIs<ModaStudyOutcome.Finished>(it.awaitResultBlocking()).run
                ).snapshot
            }
            val first = snapshots.first()
            for ((index, snapshot) in snapshots.withIndex()) {
                assertEquals(first, snapshot, "submission $index differed from the first")
            }
        }
    }

    /**
     *  Studies whose metrics carry the same names must still be kept apart, since sharing a metric
     *  between models would tie their results together.
     */
    @Test
    @DisplayName("studies sharing metric names at the same time stay separate")
    fun studiesSharingMetricNamesAtTheSameTimeStaySeparate() {
        ModaSession().use { session ->
            val cheap = documentNamed("Cheap", mapOf("A" to 10.0, "B" to 20.0, "C" to 30.0))
            val dear = documentNamed("Dear", mapOf("A" to 900.0, "B" to 800.0, "C" to 700.0))
            val handles = (1..12).map { index ->
                session.submit(if (index % 2 == 0) cheap else dear)
            }
            for ((index, handle) in handles.withIndex()) {
                val snapshot = assertIs<ModaRunResult.Completed>(
                    assertIs<ModaStudyOutcome.Finished>(handle.awaitResultBlocking()).run
                ).snapshot
                val expected = if (index % 2 == 1) "Cheap" else "Dear"
                assertEquals(expected, snapshot.name, "study $index came back as the wrong study")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Stopping and closing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a study that has already finished is not disturbed by being cancelled")
    fun aStudyThatHasAlreadyFinishedIsNotDisturbedByBeingCancelled() {
        ModaSession().use { session ->
            val handle = session.submit(standardDocument())
            val outcome = handle.awaitResultBlocking()
            assertIs<ModaStudyOutcome.Finished>(outcome)
            handle.cancel("too late")
            assertEquals(outcome, handle.awaitResultBlocking(), "cancelling changed a settled outcome")
        }
    }

    @Test
    @DisplayName("cancelling settles the outcome rather than leaving a caller waiting")
    fun cancellingSettlesTheOutcomeRatherThanLeavingACallerWaiting() {
        ModaSession().use { session ->
            val handle = session.submit(standardDocument())
            handle.cancel("changed my mind")
            val outcome = runBlocking { withTimeout(10_000) { handle.result.await() } }
            // Whether it got there first or not, it must have settled one way and stayed settled.
            assertTrue(
                outcome is ModaStudyOutcome.Cancelled || outcome is ModaStudyOutcome.Finished,
                "unexpected outcome: $outcome"
            )
            assertEquals(outcome, handle.awaitResultBlocking(), "the outcome changed after settling")
        }
    }

    @Test
    @DisplayName("closing a session stops what is still running")
    fun closingASessionStopsWhatIsStillRunning() {
        val session = ModaSession()
        val handles = (1..8).map { session.submit(standardDocument(it.toDouble())) }
        session.close()
        assertTrue(session.isClosed)
        for (handle in handles) {
            val outcome = runBlocking { withTimeout(10_000) { handle.result.await() } }
            assertTrue(
                outcome is ModaStudyOutcome.Cancelled || outcome is ModaStudyOutcome.Finished,
                "a study was left unsettled by closing: $outcome"
            )
        }
    }

    @Test
    @DisplayName("a closed session refuses politely rather than by breaking")
    fun aClosedSessionRefusesPolitelyRatherThanByBreaking() {
        val session = ModaSession()
        session.close()
        val outcome = session.submitAndAwaitBlocking(standardDocument())
        val failed = assertIs<ModaStudyOutcome.Failed>(outcome, "a closed session should refuse: $outcome")
        assertTrue(failed.message.contains("closed"), "the refusal does not say why: ${failed.message}")
    }

    @Test
    @DisplayName("closing twice does nothing the second time")
    fun closingTwiceDoesNothingTheSecondTime() {
        val session = ModaSession()
        session.submit(standardDocument())
        session.close()
        session.close()
        assertTrue(session.isClosed)
    }

    @Test
    @DisplayName("a study submitted with its own source reads from that source")
    fun aStudySubmittedWithItsOwnSourceReadsFromThatSource() {
        ModaSession().use { session ->
            val provider = ModaSourceProviderIfc {
                ModaSourceIfc { alternatives, metrics ->
                    ScoreTable(
                        alternatives.associateWith { name -> metrics.associateWith { name.length * 10.0 } },
                        emptyList()
                    )
                }
            }
            val document = standardDocument().copy(
                source = ModaSourceReference.RegisteredProvider("bespoke")
            )
            val outcome = session.submitAndAwaitBlocking(
                document, ModaSourceResolver(providers = mapOf("bespoke" to provider))
            )
            val completed = assertIs<ModaRunResult.Completed>(
                assertIs<ModaStudyOutcome.Finished>(outcome).run
            )
            assertEquals(50.0, completed.snapshot.scores["North"]!!["Cost"])
        }
    }
}
