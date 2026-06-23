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

package ksl.examples.general.models.station

import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.station.ByTypeRouter
import ksl.modeling.station.QObjectClass
import ksl.modeling.station.ShortestQueueRouter
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.divideConstant
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.random.rvariable.div

/**
 *  Enhanced STEM Career Fair Mixer (book chapter 7), reimplemented with the
 *  station framework. The chapter-7 model adds three things over chapter 6:
 *  (1) non-homogeneous Poisson arrivals (a 12-segment piecewise-constant rate
 *  function with rates peaking mid-afternoon), (2) capacity schedules on the
 *  recruiters (6 hour-long intervals each, ramping up then down), and (3)
 *  walking delays between locations driven by a sampled walking speed.
 *
 *  The framework changes that unblocked this model:
 *  - [NHPPSource] (gap 6, Phase 0/4 extension)
 *  - [ActivityStationSpec] in the DTO for pure delays (gap 8)
 *  - Capacity schedules on `SingleQStation` (Phase 2)
 *
 *  ## Simplifications from the legacy chapter-7 model
 *  - **Door-closing dynamics omitted.** The legacy has an `isClosed` flag set
 *    at `lengthOfMixer - warningTime` that students check at multiple decision
 *    points to short-circuit and walk straight to the exit. Modeling this
 *    requires a network-wide gate construct with per-class conditional routing
 *    at each delay; out of scope for this demonstration. Here the model runs
 *    for the full mixer length without closing.
 *  - **Recruiter visit pattern simplified.** The legacy has each student
 *    (non-leaver) visit *both* recruiters in an order determined by the
 *    current load. Here each student visits just one recruiter, chosen by
 *    [ShortestQueueRouter] — same join-shortest-queue dynamic, simpler flow.
 *
 *  ## Three classes (by qObjectType)
 *  - 1 = Recruiting-only student (60%): walks to recruiting, visits one
 *    recruiter, walks to exit.
 *  - 2 = Mixer student (~36%): walks to conversation area, talks, walks to
 *    recruiting, visits one recruiter, walks to exit.
 *  - 3 = Mixer-leaver (~4%): walks to conversation area, talks, walks to exit
 *    without visiting a recruiter.
 */
class StemFairEnhancedStation(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    // sampling RVs used by the marking hook (created once so streams persist)
    private val decideMixRV = BernoulliRV(0.4, 3)
    private val decideLeaveRV = BernoulliRV(0.1, 4)

    // walking speed (legacy stream 9) -- a Triangular RV; walks are distance / speed
    private val walkingSpeedRV: RVariableIfc = TriangularRV(88.0, 176.0, 264.0, 9)
    private val walkToNameTagsRV: RVariableIfc = 20.0 / walkingSpeedRV
    private val walkFromNameTagsToConversationRV: RVariableIfc = 30.0 / walkingSpeedRV
    private val walkFromNameTagsToRecruitingRV: RVariableIfc = 80.0 / walkingSpeedRV
    private val walkFromConversationToRecruitingRV: RVariableIfc = 50.0 / walkingSpeedRV
    private val walkFromConversationToExitRV: RVariableIfc = 110.0 / walkingSpeedRV
    private val walkFromRecruitingToExitRV: RVariableIfc = 60.0 / walkingSpeedRV

    init {
        // per-class statistics surface via QObjectClass registrations
        net.registerClass(QObjectClass("RecruitingOnly", typeId = 1))
        net.registerClass(QObjectClass("Mixer", typeId = 2))
        net.registerClass(QObjectClass("Leaver", typeId = 3))

        val exit = net.sink("Exit")

        // walks to exit (terminal walks for each path)
        val walkFromRecruitingToExit = net.activityStation(
            "WalkFromRecruitingToExit", walkFromRecruitingToExitRV, nextReceiver = exit
        )
        val walkFromConversationToExit = net.activityStation(
            "WalkFromConversationToExit", walkFromConversationToExitRV, nextReceiver = exit
        )

        // recruiters -- SingleQStations with capacity schedules
        val jhBunt = net.singleQStation(
            "JHBuntRecruiters", ExponentialRV(6.0, 7), capacity = 3,
            nextReceiver = walkFromRecruitingToExit
        )
        val malWart = net.singleQStation(
            "MalWartRecruiters", ExponentialRV(3.0, 8), capacity = 2,
            nextReceiver = walkFromRecruitingToExit
        )

        // capacity schedules: 6 hour-long intervals each
        val jhBuntSchedule = CapacitySchedule(this, 0.0, name = "${this.name}:JHBuntSchedule")
        intArrayOf(1, 2, 4, 7, 7, 3).forEach { jhBuntSchedule.addItem(capacity = it, duration = 60.0) }
        jhBunt.useCapacitySchedule(jhBuntSchedule)

        val malWartSchedule = CapacitySchedule(this, 0.0, name = "${this.name}:MalWartSchedule")
        intArrayOf(1, 2, 4, 6, 6, 3).forEach { malWartSchedule.addItem(capacity = it, duration = 60.0) }
        malWart.useCapacitySchedule(malWartSchedule)

        // join-shortest-queue dispatcher between the two recruiters
        val pickRecruiter = ShortestQueueRouter(listOf(jhBunt, malWart))
        net.register("PickRecruiter", pickRecruiter)

        // walks toward the recruiting area
        val walkFromNameTagsToRecruiting = net.activityStation(
            "WalkFromNameTagsToRecruiting", walkFromNameTagsToRecruitingRV, nextReceiver = pickRecruiter
        )
        val walkFromConversationToRecruiting = net.activityStation(
            "WalkFromConversationToRecruiting", walkFromConversationToRecruitingRV, nextReceiver = pickRecruiter
        )

        // post-conversation router: leavers walk to exit; mixers go to recruiting
        val postConversation = ByTypeRouter(
            mapOf(3 to walkFromConversationToExit),
            default = walkFromConversationToRecruiting
        )
        net.register("PostConversation", postConversation)

        // conversation area (mixers talk here)
        val conversation = net.activityStation(
            "Conversation", TriangularRV(10.0, 15.0, 30.0, 5), nextReceiver = postConversation
        )

        // walk from name tags to conversation (mixers + leavers)
        val walkFromNameTagsToConversation = net.activityStation(
            "WalkFromNameTagsToConversation", walkFromNameTagsToConversationRV, nextReceiver = conversation
        )

        // post-name-tag router: recruiting-only -> recruiting walk; others -> conversation walk
        val postNameTag = ByTypeRouter(
            mapOf(1 to walkFromNameTagsToRecruiting),
            default = walkFromNameTagsToConversation
        )
        net.register("PostNameTag", postNameTag)

        // name tag station
        val nameTag = net.activityStation(
            "NameTag", UniformRV(15.0 / 60.0, 45.0 / 60.0, 2), nextReceiver = postNameTag
        )
        // walk from entrance to name tags
        val walkToNameTags = net.activityStation(
            "WalkToNameTags", walkToNameTagsRV, nextReceiver = nameTag
        )

        // NHPP source: 12-segment piecewise rate function, peaking mid-afternoon
        val durations = doubleArrayOf(
            30.0, 30.0, 30.0, 30.0, 30.0, 30.0,
            30.0, 30.0, 30.0, 30.0, 30.0, 30.0
        )
        val hourlyRates = doubleArrayOf(
            5.0, 10.0, 15.0, 25.0, 40.0, 50.0,
            55.0, 55.0, 60.0, 30.0, 5.0, 5.0
        )
        val ratesPerMinute = hourlyRates.divideConstant(60.0)

        net.nhppSource(
            name = "Arrivals",
            durations = durations,
            rates = ratesPerMinute,
            firstReceiver = walkToNameTags,
            marking = { q ->
                val isMixer = decideMixRV.value > 0.5
                val isLeaver = isMixer && (decideLeaveRV.value > 0.5)
                q.qObjectType = when {
                    !isMixer -> 1            // RecruitingOnly
                    !isLeaver -> 2           // Mixer
                    else -> 3                // Leaver
                }
            }
        )
    }
}

fun main() {
    val model = Model("StemFairEnhancedStation")
    val sf = StemFairEnhancedStation(model, "Mixer")
    model.numberOfReplications = 30
    model.lengthOfReplication = 360.0    // 6 hours
    model.simulate()
    model.print()
    println()
    println("Overall system time       = ${sf.network.systemTime.acrossReplicationStatistic.average}")
    println("Recruiting-only time      = ${sf.network.classSystemTime("RecruitingOnly")?.acrossReplicationStatistic?.average}")
    println("Mixer time                = ${sf.network.classSystemTime("Mixer")?.acrossReplicationStatistic?.average}")
    println("Leaver time               = ${sf.network.classSystemTime("Leaver")?.acrossReplicationStatistic?.average}")
}
