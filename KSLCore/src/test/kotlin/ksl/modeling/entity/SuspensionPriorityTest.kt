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
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Each entity records its priority once it has come through a suspension. */
private class Suspensions(parent: ModelElement) : ProcessModel(parent, "Suspensions") {
    val pause = HoldQueue(this, "Pause")
    val go = Signal(this, "Go")
    val channel = BlockingQueue<QObject>(this, 1, "Channel")
    val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
        .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
        .build()
    val system = GuidedPathTransportSystem(this, network, name = "Sys")
    val spaceQ = HoldQueue(this, "SpaceQ")

    val observed = mutableMapOf<String, Int>()

    inner class Held(private val ownPriority: Int = 1, private val explicit: Int? = null) : Entity() {
        val p: KSLProcess = process {
            priority = ownPriority
            if (explicit == null) hold(pause) else hold(pause, explicit)
            observed["hold:$ownPriority:$explicit"] = priority
        }
    }

    inner class Syncer(private val label: String) : Entity() {
        lateinit var other: Syncer
        val p: KSLProcess = process {
            syncWith(other, pause)
            observed[label] = priority
        }
    }

    inner class SignalWaiter : Entity() {
        val p: KSLProcess = process {
            waitFor(go)
            observed["waitFor"] = priority
        }
    }

    inner class Sender(private val label: String) : Entity() {
        val p: KSLProcess = process {
            send(QObject(), channel)
            observed[label] = priority
        }
    }

    inner class Receiver : Entity() {
        val p: KSLProcess = process {
            delay(1.0)
            waitForItems(channel, 1)
            waitForItems(channel, 1)
        }
    }

    inner class ZoneHolder : Entity() {
        val p: KSLProcess = process {
            seizeZone(system, network.zone("L1.Zone1")!!, spaceQ)
            delay(1.0)
            releaseZones(system)
        }
    }

    inner class ZoneWaiter : Entity() {
        val p: KSLProcess = process {
            seizeZone(system, network.zone("L1.Zone1")!!, spaceQ)
            observed["seizeZone"] = priority
            releaseZones(system)
        }
    }

    override fun initialize() {
        val plain = Held()
        val own = Held(ownPriority = 7)
        val explicit = Held(explicit = 5)
        activate(plain.p)
        activate(own.p)
        activate(explicit.p)
        schedule<Nothing>({
            pause.removeAndResume(plain)
            pause.removeAndResume(own)
            pause.removeAndResume(explicit)
        }, 1.0)

        val first = Syncer("syncFirst")
        val second = Syncer("syncSecond")
        first.other = second
        second.other = first
        activate(first.p, timeUntilActivation = 2.0)
        activate(second.p, timeUntilActivation = 3.0)

        activate(SignalWaiter().p)
        schedule<Nothing>({ go.signalAll() }, 1.0)

        activate(Sender("sendFree").p)
        activate(Sender("sendBlocked").p)
        activate(Receiver().p)

        activate(ZoneHolder().p)
        activate(ZoneWaiter().p, timeUntilActivation = 0.5)
    }
}

/** The report's model: A pauses once and asks first, B never pauses and asks later. A should go first. */
private class Ranked(parent: ModelElement) : ProcessModel(parent, "Ranked") {
    private val pause = HoldQueue(this, "Pause")
    private val serverQ = RequestQ(this, "ServerQ", discipline = Queue.Discipline.RANKED)
    private val server = ResourceWithQ(this, "Server", queue = serverQ)
    val served = mutableListOf<String>()

    inner class Customer(val label: String, val pauseFirst: Boolean) : Entity() {
        val p: KSLProcess = process {
            if (pauseFirst) hold(pause)
            val allocation = seize(server)
            served += label
            delay(1.0)
            release(allocation)
        }
    }

    override fun initialize() {
        activate(Customer("blocker", false).p)
        val a = Customer("A", true)
        activate(a.p)
        schedule<Nothing>({ pause.removeAndResume(a) }, 0.5)
        activate(Customer("B", false).p, timeUntilActivation = 0.7)
    }
}

@DisplayName("Suspensions and the entity's priority")
class SuspensionPriorityTest {

    private fun observed(): Map<String, Int> {
        val model = Model("Suspensions${System.nanoTime()}", autoCSVReports = false)
        val element = Suspensions(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        return element.observed
    }

    @Test
    @DisplayName("hold leaves the entity's own priority alone")
    fun holdKeepsThePriority() {
        val seen = observed()
        assertEquals(1, seen["hold:1:null"])
        assertEquals(7, seen["hold:7:null"])
    }

    @Test
    @DisplayName("an explicit priority is written to the entity and stays")
    fun explicitPriorityStays() {
        assertEquals(5, observed()["hold:1:5"])
    }

    @Test
    @DisplayName("syncWith leaves the priority alone, for the first and second to arrive")
    fun syncWithKeepsThePriority() {
        val seen = observed()
        assertEquals(1, seen["syncFirst"])
        assertEquals(1, seen["syncSecond"])
    }

    @Test
    @DisplayName("waitFor on a signal leaves the priority alone")
    fun waitForSignalKeepsThePriority() {
        assertEquals(1, observed()["waitFor"])
    }

    @Test
    @DisplayName("a blocked send leaves the priority alone")
    fun blockedSendKeepsThePriority() {
        val seen = observed()
        assertEquals(1, seen["sendFree"])
        assertEquals(1, seen["sendBlocked"])
    }

    @Test
    @DisplayName("waiting for a zone leaves the priority alone")
    fun zoneWaitKeepsThePriority() {
        assertEquals(1, observed()["seizeZone"])
    }

    @Test
    @DisplayName("in a RANKED queue, a customer who paused keeps its place")
    fun rankedQueueServesInArrivalOrder() {
        val model = Model("Ranked${System.nanoTime()}", autoCSVReports = false)
        val element = Ranked(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals(listOf("blocker", "A", "B"), element.served)
    }
}
