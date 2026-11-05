package ksl.modeling.fleet

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.Bid
import ksl.modeling.fleet.policies.CallForProposals
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A vehicle answers a call for proposals from any kind of agent, and no longer drops one silently. */
class PermanentAgentCallTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Line")
            .intersection("A").intersection("B")
            .link("AB", "A", "B", length = 20.0, zoneLength = 10.0)
            .link("BA", "B", "A", length = 20.0, zoneLength = 10.0)
            .build()

        init {
            spatialModel = network
        }

        val bids = mutableListOf<Double>()
        private var called = false

        private val caller = object : AssignmentPolicyIfc {
            private val inner = NearestVehiclePolicy()
            override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
                val task = context.board.unassigned.firstOrNull()
                if (task != null && !called) {
                    called = true
                    cart.agent!!.mailbox.deliver(
                        AgentMessage.Request(desk, CallForProposals(task, context.time), conversationId = "call")
                    )
                }
                return with(inner) { assign(context) }
            }
        }

        val agv: AgvSystem = AgvSystem(this, network, assignmentPolicy = caller, name = "Agv")
        val cart: AgvVehicle = AgvVehicle(agv, TransporterPlacement.At("B"), ConstantRV(10.0), name = "Cart")

        val desk: AgentModel.PermanentAgent = agv.PermanentAgent("Desk").apply {
            statechart {
                initial("Open")
                state("Open") {
                    onMessage<AgentMessage.Propose<Bid>> { bids.add(it.proposal.value) }
                }
            }
        }

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("A")
                transportByFleet(agv, "B", origin = "A")
            }
        }

        override fun initialize() {
            called = false
            bids.clear()
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("a vehicle bids on a call from a permanent agent")
    fun vehicleBidsOnACallFromAPermanentAgent() {
        val model = Model("PermanentAgentCall", autoCSVReports = false)
        val shop = Shop(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        assertEquals(listOf(20.0), shop.bids)
    }
}
