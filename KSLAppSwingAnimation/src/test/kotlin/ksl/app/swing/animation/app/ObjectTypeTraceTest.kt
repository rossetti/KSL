package ksl.app.swing.animation.app

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.ElementKind
import ksl.animation.JsonLinesAnimationOutput
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovery C3: the object-style editor lists agent types too. Agent (and entity) types are trace-only — not in
 * the static inventory — so the controller harvests them from the most recent trace.
 */
class ObjectTypeTraceTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model = Model("Types")
    }

    @Test
    fun `object type names are harvested from the latest trace (entities and agents, distinct)`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-types")
        val controller = AnimationAppController("Types", builder).apply { workspaceOverride = ws }
        try {
            assertTrue(controller.objectTypeNamesFromLastTrace().isEmpty(), "no trace yet → no types")

            Files.createDirectories(controller.tracesDir)
            JsonLinesAnimationOutput.toFile(controller.tracesDir.resolve("t.atf")).use { out ->
                out.writeHeader(AnimationTraceHeader())
                out.writeAll(
                    listOf(
                        AnimationEvent.EntityCreated(0.0, 1L, "Customer"),
                        AnimationEvent.AgentRegistered(0.0, "a1", "Person"),
                        AnimationEvent.AgentPositionChanged(0.0, "a1", "space", 1.0, 1.0), // Person moves → animatable
                        AnimationEvent.AgentRegistered(0.0, "ctrl", "Dispatcher"),          // registered, never moves → excluded
                        AnimationEvent.EntityCreated(1.0, 2L, "Customer"), // duplicate type collapses
                    )
                )
            }
            assertEquals(setOf("Customer", "Person"), controller.objectTypeNamesFromLastTrace().toSet())
        } finally {
            controller.close()
            ws.toFile().deleteRecursively()
        }
    }

    @Test
    fun `object style type names prefer the trace's animated types over the inventory`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-styletypes")
        val controller = AnimationAppController("Types", builder).apply { workspaceOverride = ws }
        try {
            // No trace yet → fall back to the inventory's structural entity types (empty for this bare model).
            assertEquals(controller.inventory.namesOf(ElementKind.ENTITY_TYPE), controller.objectStyleTypeNames())

            Files.createDirectories(controller.tracesDir)
            JsonLinesAnimationOutput.toFile(controller.tracesDir.resolve("t.atf")).use { out ->
                out.writeHeader(AnimationTraceHeader())
                out.writeAll(listOf(
                    AnimationEvent.EntityCreated(0.0, 1L, "Customer"),
                    AnimationEvent.AgentRegistered(0.0, "a1", "Person"),
                    AnimationEvent.AgentPositionChanged(0.0, "a1", "space", 1.0, 1.0), // moving agent → animatable
                    AnimationEvent.AgentRegistered(0.0, "ctrl", "Dispatcher"),          // control agent → not offered
                ))
            }
            // With a trace, only the types actually seen animating are offered (control-only agents excluded).
            assertEquals(setOf("Customer", "Person"), controller.objectStyleTypeNames().toSet())
        } finally {
            controller.close()
            ws.toFile().deleteRecursively()
        }
    }
}
