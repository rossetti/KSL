package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.EpochProvenance
import ksl.modeling.decision.descriptor.LeverDomain
import ksl.modeling.decision.descriptor.LeverKind
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.modeling.decision.capture.TabularSink
import ksl.modeling.decision.capture.TrajectoryFile
import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  The durable sink and the trajectory it leaves behind.
 *
 *  The thing being checked is not "rows were written" but **"a file can be interpreted by something
 *  that never saw the model"**, which is the only property that makes off-line training possible.
 *  So the assertions are about what survives the boundary: the numbers, the meaning of the
 *  positions, and the refusal to guess when the meaning is missing.
 */
class TabularSinkTest {

    /** A model whose element has a categorical lever — the case where a bare number means least. */
    private class Line(parent: ModelElement, name: String, dir: Path) : ModelElement(parent, name) {

        val queue = TWResponse(this, name = "${this.name}:Queue", initialValue = 3.0)
        var speed: Double = 1.0
        var overtime: Double = 0.0

        /**
         *  The queue has to actually move, or `state` and `successorState` are identical in every
         *  row and the round-trip test cannot tell them apart — it would pass with the two columns
         *  swapped. Work arrives every 3 time units; overtime burns some off.
         */
        private fun arrival(event: ksl.simulation.KSLEvent<Nothing>) {
            queue.increment(2.0)
            if (queue.value > overtime) queue.decrement(overtime) else queue.value = 0.0
            schedule(this::arrival, 3.0)
        }

        override fun initialize() {
            schedule(this::arrival, 3.0)
        }

        lateinit var sink: TabularSink
            private set

        val review: DecisionElement = decisionElement("${this.name}:Review") {
            observe(queue, unit = "jobs")
            lever(this@Line, levels = listOf("slow", "normal", "fast"),
                neutral = Neutral.Current { speed }, alias = "Mode") { v -> speed = v }
            lever(this@Line, 0.0..8.0, neutral = Neutral.Value(0.0),
                alias = "Overtime", unit = "hours") { v -> overtime = v }
            reward(queue, rate = 2.0, sense = RewardSense.COST, alias = "Waiting")
            captureTo { provenance ->
                TabularSink(provenance, dir.resolve("${provenance.experimentName}-trajectory"))
                    .also { sink = it }
            }
            policy = PolicyIfc { _, _ -> doubleArrayOf(2.0, 3.0) }
        }.reviewEvery(this, 10.0)
    }

    private fun tempDir(label: String): Path =
        Files.createTempDirectory("ksl-traj-$label")

    private fun run(dir: Path, experiment: String = "run1"): Pair<Model, Line> {
        val m = Model("Trace")
        val line = Line(m, "L", dir)
        m.experimentName = experiment
        m.numberOfReplications = 2
        m.lengthOfReplication = 45.0
        m.simulate()
        return m to line
    }

    // ------------------------------------------------------------------ the round trip

    /**
     *  The property the whole sink exists for: everything written comes back, and it comes back
     *  **without a `Model`**. The reader is constructed from a path alone.
     */
    @Test
    fun aTrajectoryReadsBackWithNoLiveModelAndLosesNothing() {
        val dir = tempDir("roundtrip")
        val (_, line) = run(dir)
        val rowsPath = line.sink.rowsPath
        val expected = line.sink.rowsWritten

        println()
        println("wrote $expected transitions to ${rowsPath.fileName}")
        println("provenance beside it: ${line.sink.provenancePath.fileName}")

        // Nothing from here on touches the model or the element.
        TrajectoryFile(rowsPath).use { t ->
            assertEquals(expected, t.rowCount, "every row written must be readable")
            val rows = t.transitions()
            assertEquals(expected.toInt(), rows.size)
            assertTrue(rows.isNotEmpty(), "the run produced no transitions, so this asserts nothing")

            // Both replications are present and distinguishable.
            assertEquals(setOf(1, 2), rows.map { it.replicationId }.toSet())

            val r = rows.first()
            println("first row: rep=${r.replicationId} epoch=${r.epochIndex} " +
                "s=${r.state.toList()} a=${r.action.toList()} reward=${r.reward} " +
                "sp=${r.successorState.toList()} truncated=${r.truncated}")

            assertEquals(1, r.state.size, "one declared observation")
            assertEquals(2, r.action.size, "two declared levers")
            assertEquals(doubleArrayOf(2.0, 3.0).toList(), r.action.toList(),
                "the rule asked for (2, 3) at every epoch and both levers accept it")
            assertTrue(rows.any { it.truncated }, "the run length cuts an episode off, so some row " +
                "must be marked truncated — otherwise the flag is not being carried")

            // S§C.11.3 — the two fields that let a stranger interpret where a row's state came
            // from must survive the round trip. They are TEXT columns, so a silent failure here is
            // an empty string rather than a wrong number, which no other assertion would catch.
            assertTrue(rows.all { it.reason.isNotBlank() },
                "every row must carry the reason of the epoch that opened its interval; a blank " +
                    "one means the column was written but not read, or not written at all")
            assertEquals(setOf(EpochProvenance.IMMEDIATE), rows.map { it.provenance }.toSet(),
                "this element is driven by a caller through decide(), so every row's state was read " +
                    "at that caller's call site")

            // state and successor must actually differ somewhere, or the round trip would pass
            // with the two column families swapped and nobody would know.
            assertTrue(rows.any { !it.state.contentEquals(it.successorState) },
                "every row has state == successorState, so this test cannot distinguish the two " +
                    "column families. Make the model's observed quantity move")
        }
    }

    /**
     *  **The reason the provenance file exists.** Read back, the action column holds `2.0`. What
     *  makes that interpretable is the descriptor, and the reader carries it.
     */
    @Test
    fun theProvenanceMakesAPositionalRowInterpretable() {
        val dir = tempDir("meaning")
        val (_, line) = run(dir)

        TrajectoryFile(line.sink.rowsPath).use { t ->
            val d = t.descriptor
            val mode = d.levers.first { it.name == "Mode" }
            val row = t.transitions().first()
            val index = row.action[0].toInt()

            println()
            println("the file says a_Mode = ${row.action[0]}")
            println("the provenance says that is '${mode.levels!![index]}' — a ${mode.domain} " +
                "${mode.kind} over ${mode.levels}")

            assertEquals(LeverDomain.CATEGORICAL, mode.domain)
            assertEquals(listOf("slow", "normal", "fast"), mode.levels)
            assertEquals("fast", mode.levels!![index],
                "without this list, action 2.0 is a number rather than a decision")

            // And the facts a learner needs about the dynamics and the action space.
            val overtime = d.levers.first { it.name == "Overtime" }
            assertEquals(LeverKind.TRANSACTION, overtime.kind,
                "repeating a TRANSACTION acts again; repeating a SETTING does not. A learner " +
                    "modelling the dynamics needs to know which it is holding")
            assertEquals(0.0 to 8.0, overtime.lowerBound to overtime.upperBound,
                "the captured rows only ever show 3.0, so the feasible range is NOT inferable " +
                    "from the data and has to be carried")

            // And who produced them.
            assertEquals("run1", t.provenance.experimentName)
            assertEquals("L:Review", t.provenance.elementName)
        }
    }

    /** A trajectory whose meaning is missing must be refused, not guessed at. */
    @Test
    fun aTrajectoryWithoutItsProvenanceIsRefused() {
        val dir = tempDir("orphan")
        val (_, line) = run(dir)
        Files.delete(line.sink.provenancePath)

        val t = assertFailsWith<IllegalArgumentException> { TrajectoryFile(line.sink.rowsPath) }
        println()
        println("orphaned trajectory: ${t.message}")
        assertTrue(t.message!!.contains("provenance"),
            "the message must say what is missing and why it matters")
    }

    // ------------------------------------------------------------------ the schema

    /**
     *  Column names are reduced to what `CREATE TABLE` accepts. This is not hypothetical: KSL
     *  element names are colon-qualified, and a `:` fails the create outright while a space
     *  silently produces a column of the wrong name.
     */
    @Test
    fun columnNamesAreReducedToWhatSqlAccepts() {
        assertEquals("s_L_Queue", TabularSink.stateCol("L:Queue"))
        assertEquals("sp_L_Queue", TabularSink.successorCol("L:Queue"))
        assertEquals("a_Order_Qty", TabularSink.actionCol("Order Qty"))
        assertEquals("p_A_B", TabularSink.proposedCol("A-B"))
        assertTrue(TabularSink.sanitize("Room:Position").all { it.isLetterOrDigit() || it == '_' })

        val dir = tempDir("names")
        val (_, line) = run(dir)
        // The real file was created at all, which is the assertion: the element's observation is
        // named "L:Queue" and an unsanitised name would have thrown at construction.
        assertTrue(Files.exists(line.sink.rowsPath))
        println()
        println("created ${line.sink.rowsPath.fileName} with a colon-qualified observation name")
    }

    /** Two declarations that reduce to one column are refused, with both originals named. */
    @Test
    fun aNameCollisionCausedByReductionIsRefused() {
        val m = Model("Collide")
        val e = object : ModelElement(m, "C") {
            val a = TWResponse(this, name = "C:X:Y", initialValue = 1.0)
            val b = TWResponse(this, name = "C:X_Y", initialValue = 1.0)
        }
        val element = e.decisionElement("C:Review") {
            observe(e.a)                                   // C:X:Y  -> s_C_X_Y
            observe(e.b)                                   // C:X_Y  -> s_C_X_Y
            lever(e, 0.0..1.0, neutral = Neutral.Value(0.0), alias = "L") { }
            policy = NeutralPolicy
        }.reviewEvery(e, 10.0)
        val dir = tempDir("collide")
        val provenance = RunProvenance("Collide", "e", "C:Review", "p", element.descriptor())

        val t = assertFailsWith<IllegalArgumentException> {
            TabularSink(provenance, dir.resolve("t"))
        }
        println()
        println("collision refused: ${t.message}")
        assertTrue(t.message!!.contains("C:X:Y") && t.message!!.contains("C:X_Y"),
            "the message must name BOTH originals — that is the only way to know which to rename")
    }

    /**
     *  The nullable fields have an encoding rather than a null, and the encoding must be readable.
     *  With nothing repaired, `p_*` equals `a_*` and `repaired` is 0 — so a consumer can tell
     *  "not repaired" from "repaired to the same value" by the flag rather than by comparing.
     */
    @Test
    fun theNullableFieldsAreEncodedAndReadBack() {
        val dir = tempDir("nulls")
        val (_, line) = run(dir)
        TrajectoryFile(line.sink.rowsPath).use { t ->
            val rows = t.transitions()
            println()
            println("repaired flags: ${rows.map { it.repaired }.distinct()}")
            assertTrue(rows.none { it.repaired },
                "the rule asks for a feasible action every epoch, so nothing should be repaired")
        }
    }

    /** Two experiments on one model write two trajectories, and neither overwrites the other. */
    @Test
    fun eachExperimentGetsItsOwnTrajectory() {
        val dir = tempDir("two")
        val m = Model("Trace")
        val line = Line(m, "L", dir)
        m.numberOfReplications = 1
        m.lengthOfReplication = 45.0

        m.experimentName = "first"
        m.simulate()
        val first = line.sink.rowsPath
        val firstRows = line.sink.rowsWritten

        m.experimentName = "second"
        m.simulate()
        val second = line.sink.rowsPath

        println()
        println("first : ${first.fileName} ($firstRows rows)")
        println("second: ${second.fileName} (${line.sink.rowsWritten} rows)")

        assertTrue(first != second, "a second experiment must not write over the first's trajectory")
        assertTrue(Files.exists(first) && Files.exists(second))
        TrajectoryFile(first).use { assertEquals(firstRows, it.rowCount) }
        TrajectoryFile(second).use { assertEquals(line.sink.rowsWritten, it.rowCount) }
    }
}
