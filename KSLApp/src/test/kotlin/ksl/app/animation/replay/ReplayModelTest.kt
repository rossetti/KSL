package ksl.app.animation.replay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.app.session.AnimationTraceAttachment
import ksl.app.animation.io.AnimationSource
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ksl.app.animation.io.load

/**
 * Keystone headless test: generate a real two-file trace from a process model via the
 * AnimationTraceAttachment, load it with [AnimationSource], build a [ReplayModel], and verify it
 * answers frame queries (queue length, resource state, response value, entity existence) at time t.
 */
class ReplayModelTest {

    @TempDir
    lateinit var tempRoot: Path

    private class ShopModel(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val worker = ResourceWithQ(parent = this, name = "Worker", capacity = 1)
        private val st = RandomVariable(this, ExponentialRV(0.8, 2))
        private val tba = ExponentialRV(0.6, 1)
        private val tip = Response(this, name = "TimeInSystem")

        @Suppress("unused")
        private val generator = EntityGenerator(::Cust, tba, tba)

        private inner class Cust : Entity() {
            @Suppress("unused")
            val proc: KSLProcess = process(isDefaultProcess = true) {
                val arrival = time
                val a = seize(worker)
                delay(st)
                release(a)
                tip.value = time - arrival
            }
        }
    }

    @Test
    fun `builds a queryable replay model from a real two-file trace`() {
        val tmp = Files.createTempDirectory(tempRoot, "replaytest")
        val traceFile = tmp.resolve("run.atf")
        val layoutFile = tmp.resolve("run.lay.json")

        val model = Model("shop", pathToOutputDirectory = tmp)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        ShopModel(model)

        val attachment = AnimationTraceAttachment.replay(
            traceFile = traceFile,
            layout = AnimationLayout(title = "Shop"),
            layoutFile = layoutFile
        )
        attachment.onAttach(model, CoroutineScope(SupervisorJob()))
        try {
            model.simulate()
        } finally {
            attachment.onDetach()
        }

        val replay = ReplayModel.build(AnimationSource.load(layoutFile, traceFile))

        // Layout loaded.
        assertEquals("Shop", replay.layout?.title)
        // Time range covers the run.
        assertTrue(replay.timeRange.endInclusive > 0.0, "time range should span the run")
        // All three series captured (names not hard-coded to avoid incidental coupling).
        assertTrue(replay.queueNames.isNotEmpty(), "expected a queue series")
        assertTrue(replay.resourceNames.isNotEmpty(), "expected a resource series")
        assertTrue(replay.responseNames.isNotEmpty(), "expected a response series")

        val tMid = replay.timeRange.endInclusive / 2.0

        // Queue length is a valid count.
        assertTrue(replay.queueLengthAt(replay.queueNames.first(), tMid) >= 0)

        // Entities exist mid-run and are the expected type.
        val live = replay.entitiesAt(tMid)
        assertTrue(live.isNotEmpty(), "entities should exist mid-run")
        assertTrue(live.all { it.typeName == "Cust" }, "entity type should be the runtime class name")

        // Resource state mid-run is known and busy/idle-ish (the state name may be resource-qualified,
        // e.g. "Worker_Busy"), with busy units within capacity.
        val rs = replay.resourceStateAt(replay.resourceNames.first(), tMid)
        assertTrue(rs != null, "resource state should be known mid-run")
        assertTrue(rs!!.busyUnits in 0..rs.capacity, "busy units within capacity: $rs")
        assertTrue(
            rs.state.contains("Busy", ignoreCase = true) || rs.state.contains("Idle", ignoreCase = true),
            "resource state should be busy/idle: ${rs.state}"
        )

        // A response value is available by the end of the run.
        assertTrue(
            replay.responseValueAt(replay.responseNames.first(), replay.timeRange.endInclusive) != null,
            "a response value should be observed by the end of the run"
        )
    }

    @Test
    fun `tracks current process and activity label`() {
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Patient"),
            AnimationEvent.ProcessActivated(1.0, 1L, "Triage"),
            AnimationEvent.ProcessCompleted(2.0, 1L, "Triage"),
            AnimationEvent.ProcessActivated(3.0, 1L, "Exam")
        )
        val r = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

        // Current process over time (null before the first, and between completed and next activation).
        assertEquals(null, r.entityProcessAt(1L, 0.5))
        assertEquals("Triage", r.entityProcessAt(1L, 1.5))
        assertEquals(null, r.entityProcessAt(1L, 2.5))
        assertEquals("Exam", r.entityProcessAt(1L, 3.5))

        // Tier-2 activity label: the process when in one, else "idle".
        assertEquals("Triage", r.entityActivityLabelAt(1L, 1.5))
        assertEquals("idle", r.entityActivityLabelAt(1L, 2.5))
    }
}
