package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ScoringAssignmentPolicy
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A candidate-scoring policy is interchangeable with the rule it generalises.
 *
 *  `NearestVehiclePolicy` is a rule: for each task, send the nearest vehicle. `ScoringAssignmentPolicy`
 *  is a shape: enumerate the feasible actions, score each, take the best. The second subsumes the
 *  first, and the way to show that is to run both on the same model and get the same answer to the
 *  digit -- not to argue that they must agree.
 *
 *  Agreement needs the scoring policy to be given the rule's own tie-breaking, which is the part
 *  worth being explicit about. `NearestVehiclePolicy` walks the tasks in selection-rule order and
 *  picks a vehicle for each; a scoring policy that took the globally best pairing first would order
 *  its work differently and would be a *different, defensible* rule rather than the same one. The
 *  score below therefore ranks by task position first and distance second, which is the rule
 *  written as a score.
 *
 *  That this needs saying is the finding: "score by distance" is not by itself the same policy, and
 *  a study that swapped one for the other expecting no change would have been comparing two rules
 *  while believing it had changed only a representation.
 */
class ScoringPolicyTest {

    private class Shop(parent: ModelElement, policy: AssignmentPolicyIfc) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        val carts = listOf(SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.AGV2_HOME)
            .mapIndexed { i, home ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                    .apply { homeBase = home }
            }

        val waits = Statistic("waits")
        val carriers = mutableListOf<String>()

        private var issued = 0

        inner class Part : Entity() {
            private val label = "Load${issued++}"

            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val r = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                waits.collect(r.waitForAssignment + r.waitForArrival)
                // Load and carrier together. The carrier alone is a weak probe -- two carts alternate
                // under any rule, so their names come out in the same order however the tasks were
                // ordered, and an equality check on them would pass for policies that differ.
                carriers.add("$label/${r.vehicleName}")
            }
        }

        private val tba = ExponentialRV(45.0, streamNum = 1)

        inner class Source : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(40) { delay(tba); activate(Part().p) }
            }
        }

        override fun initialize() {
            waits.reset()
            carriers.clear()
            issued = 0
            activate(Source().g)
        }
    }

    private fun run(policy: AssignmentPolicyIfc): Shop {
        val m = Model("Scoring")
        val shop = Shop(m, policy)
        m.numberOfReplications = 1
        m.lengthOfReplication = 2000.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("A scoring policy reproduces NearestVehiclePolicy exactly when given its rule as a score")
    fun scoringByDistanceReproducesTheRule() {
        val rule = run(NearestVehiclePolicy())

        // The rule expressed as a score: task order dominates, distance decides within a task.
        // Multiplying the task's position by a number larger than any distance on this network makes
        // the ordering lexicographic without needing a comparator.
        val asScore = run(ScoringAssignmentPolicy { proposal, set ->
            val position = set.outstanding.indexOfFirst { it === proposal.task }
            position * 1_000_000.0 + set.cost(proposal.vehicle, proposal.task)
        })

        assertTrue(rule.carriers.size > 20, "too little work to compare: ${rule.carriers.size}")
        assertEquals(rule.carriers, asScore.carriers,
            "the same rule written as a score sent different vehicles")
        assertEquals(rule.waits.average, asScore.waits.average, 1e-9,
            "the same rule written as a score gave a different answer")
        assertEquals(rule.waits.count, asScore.waits.count)
    }

    @Test
    @DisplayName("Scoring globally instead is a different and defensible rule, not a broken one")
    fun globalScoringIsADifferentRule() {
        // Two conditions are needed before these policies can differ at all, and finding them was
        // most of the work in this test.
        //
        // **Two pickup points.** On a single-origin network every task costs a given vehicle the
        // same, so ranking by task position and ranking globally cannot disagree. A study comparing
        // these two shapes on a single-origin model would correctly find no difference and would be
        // entitled to conclude nothing from it.
        //
        // **A board that has backed up.** A newly posted task wakes the dispatcher, and that wake
        // wins the priority tie against the next arrival's activation -- so tasks posted at the same
        // instant are still dispatched one at a time, each pass seeing a board of one. With a board
        // of one there is nothing to rank. The distinction appears only when vehicles are busy and
        // several tasks are waiting when one frees up, which is the condition under which a modeller
        // would care about it anyway.
        //
        // **And a drop point that does not decide the answer.** With the load set down immediately
        // before the far pickup, a freed cart is always nearest the far task -- so "first in the
        // queue" and "nearest" name the same task and the comparison is unfalsifiable however busy
        // the fleet is. The alternative drop sits between the two pickups instead. That this had to
        // be found by instrumenting a run, rather than reasoned about, is the useful part: a layout
        // can make two genuinely different policies indistinguishable without anything looking wrong.
        fun runRing(policy: AssignmentPolicyIfc): List<String> {
            val m = Model("ScoringRing")
            val shop = object : ProcessModel(m, "Shop") {
                val network = AgvTestNetworks.ringWithTwoParks()

                init {
                    spatialModel = network
                }

                val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")
                val carts = listOf(AgvTestNetworks.DEPOT, AgvTestNetworks.SECOND_DEPOT)
                    .mapIndexed { i, home ->
                        AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                            .apply { homeBase = home }
                    }
                val carriers = mutableListOf<String>()

                inner class Load(val label: String, val from: String) : Entity(label) {
                    val p = process(isDefaultProcess = true) {
                        currentLocation = network.requireLocation(from)
                        val r = transportByFleet(agv, AgvTestNetworks.ALT_DROP, origin = from)
                        // The *load* delivered, not the vehicle that carried it. Which cart does the
                        // work is a poor probe: with two carts they alternate under any rule, so the
                        // sequence of carrier names is the same however the tasks were ordered. What
                        // an ordering decision changes is which load goes when.
                        carriers.add("$label/${r.vehicleName}")
                    }
                }

                override fun initialize() {
                    carriers.clear()
                    // A burst of alternating origins against two carts, so the board backs up and a
                    // freed cart faces a genuine choice among several waiting tasks.
                    for (i in 0 until 10) {
                        val t = i * 8.0
                        activate(Load("Far$i", AgvTestNetworks.FAR).p, timeUntilActivation = t)
                        activate(Load("Near$i", AgvTestNetworks.NEAR).p, timeUntilActivation = t + 4.0)
                    }
                }
            }
            m.numberOfReplications = 1
            m.lengthOfReplication = 3000.0
            m.simulate()
            return shop.carriers
        }

        val byRule = runRing(NearestVehiclePolicy())
        val byGlobal = runRing(ScoringAssignmentPolicy { p, f -> f.cost(p.vehicle, p.task) })

        assertTrue(byRule.size > 10, "too little work to compare: ${byRule.size}")
        // Both are legitimate; they simply are not each other. If they ever coincide on this model,
        // the comparison has stopped testing anything and should be re-posed.
        assertTrue(
            byRule != byGlobal,
            "global scoring gave exactly the rule's answer even on a two-pickup layout, so the two " +
                    "shapes are no longer distinguishable and this test documents nothing"
        )
    }
}
