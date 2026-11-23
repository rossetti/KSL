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

package ksl.modeling.entity.stress

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.guiderules.RuleModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Runs scenarios of the guide-rule tests whose outcome the claim-order decisions settle, with the reference
 *  allocator watching every offer: the library serves whom the reference says, in each.
 */
class ReferenceAllocatorKnownFaultsTest {

    private var myInvariantsWereEnabled = false
    private var myObserverBefore: ResourceInvariants.OfferObserver? = null

    @BeforeEach
    fun setUp() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = true
        myObserverBefore = ResourceInvariants.offerObserver
    }

    @AfterEach
    fun tearDown() {
        ResourceInvariants.offerObserver = myObserverBefore
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    private fun RuleModel.job(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int = 1) =
        actor(tag, priority) {
            delay(from); val a = seize(r, n); log.add("$tag got@$time"); delay(hold); release(a); log.add("$tag done@$time")
        }

    private fun RuleModel.urgent(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int, rule: InterruptRule) =
        actor(tag, priority) {
            delay(from); val a = preempt(r, n, rule = rule); log.add("$tag got@$time"); delay(hold); release(a); log.add("$tag done@$time")
        }

    /** Runs [build] with the reference watching, and returns the differences found. */
    private fun watch(name: String, length: Double, build: RuleModel.() -> Unit): List<ReferenceOfferObserver.Difference> {
        val observer = ReferenceOfferObserver()
        ResourceInvariants.offerObserver = observer
        val m = RuleModel(name)
        m.build()
        m.simulate(length)
        ResourceInvariants.offerObserver = myObserverBefore
        println("== $name: ${observer.offersJudged} offers judged, skipped ${observer.skipped}, log ${m.log}")
        observer.differences.forEach { println("   $it") }
        return observer.differences
    }

    private val cases: Map<String, Pair<Double, RuleModel.() -> Unit>> = linkedMapOf(
        "LoanEnd.moreUrgentWaiterNeedingOnlyFreeUnits" to (300.0 to {
            val r = ResourceWithQ(this, "R", capacity = 3)
            plan {
                job("L", r, 0.0, 100.0, priority = 5, n = 2)
                urgent("B", r, 1.0, 3.0, priority = 0, n = 3, rule = InterruptRule.RESUME)
                job("R", r, 1.5, 1.0, priority = 9)
                urgent("C", r, 2.0, 5.0, priority = 2, n = 3, rule = InterruptRule.RESUME)
                urgent("W", r, 3.0, 1.0, priority = 1, n = 1, rule = InterruptRule.RESUME)
            }
        }),
        "LoanEnd.finishWaiterAndEquallyUrgentReservation" to (300.0 to {
            val r = ResourceWithQ(this, "R", capacity = 4)
            plan {
                job("L", r, 0.0, 100.0, priority = 5, n = 2)
                job("H", r, 0.1, 100.0, priority = 3, n = 1)
                urgent("B", r, 0.2, 2.8, priority = 0, n = 3, rule = InterruptRule.RESUME)
                urgent("W", r, 1.0, 1.0, priority = 2, n = 3, rule = InterruptRule.FINISH)
                urgent("V", r, 2.0, 10.0, priority = 2, n = 1, rule = InterruptRule.FINISH)
            }
        }),
        "LoanEnd.equalTimeTieGoesToTheFirstWaiter" to (300.0 to {
            val r = ResourceWithQ(this, "R", capacity = 3)
            plan {
                job("L", r, 0.0, 100.0, priority = 5, n = 2)
                urgent("B", r, 0.2, 2.8, priority = 0, n = 3, rule = InterruptRule.RESUME)
                urgent("W", r, 2.0, 1.0, priority = 2, n = 2, rule = InterruptRule.FINISH)
                urgent("C", r, 2.0, 1.0, priority = 2, n = 2, rule = InterruptRule.RESUME)
            }
        }),
        "LoanEnd.returningUnitsAfterAReservationIsServed" to (300.0 to {
            val r = ResourceWithQ(this, "R", capacity = 4)
            plan {
                job("L", r, 0.0, 100.0, priority = 5, n = 2)
                job("H", r, 0.1, 100.0, priority = 3, n = 1)
                urgent("B", r, 0.2, 2.8, priority = 0, n = 3, rule = InterruptRule.RESUME)
                urgent("V", r, 1.0, 10.0, priority = 2, n = 1, rule = InterruptRule.FINISH)
                urgent("W", r, 2.0, 1.0, priority = 2, n = 2, rule = InterruptRule.RESUME)
            }
        }),
        "LoanEnd.returningUnitsAfterAMoreUrgentRequestIsServed" to (300.0 to {
            val r = ResourceWithQ(this, "R", capacity = 3)
            plan {
                job("L", r, 0.0, 100.0, priority = 5, n = 2)
                urgent("B", r, 0.2, 2.8, priority = 0, n = 3, rule = InterruptRule.RESUME)
                job("N", r, 1.0, 10.0, priority = 1)
                urgent("W", r, 2.0, 1.0, priority = 2, n = 2, rule = InterruptRule.RESUME)
            }
        }),
        "LoanEnd.moreUrgentReservationTakesTheReturningUnits" to (300.0 to {
            val r = ResourceWithQ(this, "R", capacity = 4)
            plan {
                job("L", r, 0.0, 100.0, priority = 5, n = 2)
                job("H", r, 0.1, 100.0, priority = 2, n = 2)
                urgent("B", r, 0.2, 2.8, priority = 0, n = 2, rule = InterruptRule.RESUME)
                urgent("V", r, 1.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
                urgent("W", r, 2.0, 1.0, priority = 3, n = 2, rule = InterruptRule.FINISH)
            }
        }),
        "Cases.borrowerOfEqualPriorityNotPreempted" to (50.0 to {
            val r = ResourceWithQ(this, "R")
            plan {
                job("L", r, 0.0, 10.0, priority = 9)
                urgent("B", r, 1.0, 5.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
                urgent("P", r, 2.0, 1.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
            }
        }),
        "Cases.restartFailureAndRestartPreemptorAtARepair" to (50.0 to {
            val r = ResourceWithQ(this, "R")
            plan {
                job("L", r, 0.0, 10.0, priority = 9)
                at(1.0) { r.fail(InterruptRule.RESTART) }
                urgent("P", r, 2.0, 1.0, priority = 1, n = 1, rule = InterruptRule.RESTART)
                at(4.0) { r.repair() }
            }
        }),
        "Cases.loanEndWithAWaitingFinishPreemptor" to (50.0 to {
            val r = ResourceWithQ(this, "R")
            plan {
                job("L", r, 0.0, 10.0, priority = 9)
                urgent("B", r, 1.0, 5.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
                urgent("P", r, 2.0, 1.0, priority = 1, n = 1, rule = InterruptRule.FINISH)
            }
        }),
        "Cases.freedAndReturningUnitsCompleteAReservation" to (100.0 to {
            val r = ResourceWithQ(this, "R", capacity = 2)
            plan {
                job("L", r, 0.0, 20.0, priority = 9)
                urgent("B", r, 1.0, 5.0, priority = 3, n = 2, rule = InterruptRule.RESUME)
                urgent("F", r, 2.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
            }
        }),
        "Cases.freedUnitsGoToTheMoreUrgentReservation" to (200.0 to {
            val r = ResourceWithQ(this, "R", capacity = 4)
            plan {
                job("A", r, 0.0, 20.0, priority = 10)
                job("B", r, 0.5, 20.0, priority = 9, n = 2)
                urgent("P", r, 1.0, 5.0, priority = 5, n = 2, rule = InterruptRule.RESUME)
                urgent("R1", r, 2.0, 1.0, priority = 2, n = 3, rule = InterruptRule.FINISH)
                urgent("R2", r, 3.0, 30.0, priority = 3, n = 2, rule = InterruptRule.FINISH)
            }
        }),
        "Cases.freedUnitsGoToTheMoreUrgentWaitingPreemptor" to (200.0 to {
            val r = ResourceWithQ(this, "R", capacity = 4)
            plan {
                job("A", r, 0.0, 20.0, priority = 10)
                job("B", r, 0.5, 20.0, priority = 9, n = 2)
                urgent("P", r, 1.0, 5.0, priority = 3, n = 2, rule = InterruptRule.RESUME)
                urgent("F", r, 2.0, 1.0, priority = 4, n = 4, rule = InterruptRule.FINISH)
                urgent("R", r, 3.0, 30.0, priority = 6, n = 2, rule = InterruptRule.FINISH)
            }
        }),
        "Cases.returningUnitsPartlyCompletingAReservationGoBack" to (100.0 to {
            val r = ResourceWithQ(this, "R", capacity = 2)
            plan {
                job("L1", r, 0.0, 20.0, priority = 9)
                job("L2", r, 0.5, 20.0, priority = 9)
                urgent("B", r, 1.0, 5.0, priority = 3, n = 1, rule = InterruptRule.RESUME)
                urgent("F", r, 2.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
            }
        }),
        "Cases.resumePreemptorBeyondTheCapacityBlocksNoOne" to (50.0 to {
            val r = ResourceWithQ(this, "R", capacity = 3)
            plan {
                at(1.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
                urgent("P", r, 2.0, 1.0, priority = 1, n = 2, rule = InterruptRule.RESUME)
                job("N", r, 3.0, 17.0, priority = 5)
                at(5.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
            }
        }),
        "Cases.finishPreemptorBeyondTheCapacityReservesAgain" to (50.0 to {
            val r = ResourceWithQ(this, "R", capacity = 2)
            plan {
                job("A", r, 0.0, 10.0, priority = 9)
                urgent("P", r, 1.0, 1.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
                at(2.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
                job("N", r, 3.0, 20.0, priority = 5)
                at(15.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
            }
        }),
        // A FINISH reservation is ranked against a queued request by priority. R has 1 unit; L (priority 9)
        // holds it; F (priority 3, FINISH) reserves it at 1; N (priority 1) seizes at 2. At L's release, 10, N is
        // more urgent than F's reservation and is served first.
        "reservationRankedBelowAMoreUrgentQueuedRequest" to (100.0 to {
            val r = ResourceWithQ(this, "R")
            plan {
                job("L", r, 0.0, 10.0, priority = 9)
                urgent("F", r, 1.0, 1.0, priority = 3, n = 1, rule = InterruptRule.FINISH)
                job("N", r, 2.0, 1.0, priority = 1)
            }
        }),
    )

    /** The scenarios the library decides against the reference: none. */
    private val expectedFlagged = emptySet<String>()

    @Test
    @DisplayName("on the scenarios whose outcome the claim order settles, every offer serves whom the reference says")
    fun flagsTheKnownFaults() {
        val flagged = mutableMapOf<String, Set<String>>()
        for ((name, case) in cases) {
            val d = watch(name, case.first, case.second)
            if (d.isNotEmpty()) flagged[name] = d.map { it.kind }.toSet()
        }
        println("FLAGGED:\n" + flagged.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        assertEquals(expectedFlagged, flagged.keys, "flagged: $flagged")
    }
}
