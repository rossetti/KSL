package ksl.animation

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G1: an agent `Context.location(...)` declaration is extracted into the inventory with its position, so
 * auto-layout can surface and place it — no hand-authored layout or DSL.
 */
class AgentLocationInventoryTest {

    private class TinyAgents(parent: ModelElement) : AgentModel(parent, "tiny") {
        val sky = Context<AgentLike>("space")
        init {
            sky.location("Depot", 10.0, 20.0)
            sky.location("Drop", 30.0, 40.0)
        }
    }

    @Test
    fun `Context named locations reach inventory locationInfos with positions`() {
        val m = Model("t")
        TinyAgents(m)
        val inv = m.animationInventory()
        assertTrue(inv.locations.containsAll(listOf("Depot", "Drop")), "names surface in locations: ${inv.locations}")
        assertEquals(LocationInfo("Depot", 10.0, 20.0), inv.locationInfos.first { it.name == "Depot" })
        assertEquals(LocationInfo("Drop", 30.0, 40.0), inv.locationInfos.first { it.name == "Drop" })
    }

    /** G3: an agent type is tagged [EntityTypeInfo.isAgent]; a plain process entity is not. */
    private class MixedModel(parent: ModelElement) : AgentModel(parent, "mixed") {
        @Suppress("unused") inner class Drone : Agent("d")   // AgentModel.Agent subclass -> isAgent
        @Suppress("unused") inner class Widget : Entity()    // plain process entity     -> not
    }

    @Test
    fun `agent types are tagged isAgent, process entities are not`() {
        val m = Model("t")
        MixedModel(m)
        val inv = m.animationInventory()
        assertTrue(inv.entityTypes.first { it.typeName == "Drone" }.isAgent, "Drone is an Agent subclass")
        assertFalse(inv.entityTypes.first { it.typeName == "Widget" }.isAgent, "Widget is a plain process entity")
    }
}
