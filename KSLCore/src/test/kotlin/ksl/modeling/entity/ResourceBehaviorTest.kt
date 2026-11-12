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

package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.AnimationTraceHeader
import ksl.animation.TraceFileReader
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A resource's own behavior: declared once, started at time 0 of every replication by a fresh
 *  entity of the resource, ended with the replication, and stopped and started by the model when it
 *  wants. The cyclic form repeats its block and guards against a cycle that never waits.
 */
class ResourceBehaviorTest {

    private class BModel(parent: ModelElement) : ProcessModel(parent, "behaviorModel") {
        val doctor = ResourceWithQ(this, "Doctor")
        val other = ResourceWithQ(this, "Other")
        val signal = Signal(this, "Sig")
        val hq = HoldQueue(this, "HQ")
        val log = mutableListOf<String>()
        val setups = mutableListOf<BModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun customer(tag: String, from: Double, hold: Double) = actor(tag) {
            delay(from)
            val a = seize(doctor)
            log.add("$tag:served@$time")
            delay(hold)
            release(a)
        }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    /** Builds the model, lets [declare] declare behaviors before the run, then simulates. */
    private fun run(
        reps: Int = 1,
        length: Double = 50.0,
        warmUp: Double = 0.0,
        sink: AnimationSink? = null,
        declare: BModel.() -> Unit = {},
        setup: BModel.() -> Unit = {}
    ): BModel {
        val model = Model("Behavior${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = BModel(model)
        m.declare()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return m
    }

    // ── Running each replication ────────────────────────────────────────────

    @Test
    @DisplayName("a cyclic behavior starts at time 0 of every replication and repeats its block")
    fun cyclicRunsEachReplication() {
        val seen = mutableListOf<String>()
        run(reps = 2, length = 25.0, declare = {
            doctor.cyclicBehavior { seen.add("cycle@$time"); delay(10.0) }
        })
        assertEquals(List(2) { listOf("cycle@0.0", "cycle@10.0", "cycle@20.0") }.flatten(), seen)
    }

    @Test
    @DisplayName("a one-shot behavior runs once per replication, with its local state fresh; captured state carries over")
    fun oneShotLocalAndCapturedState() {
        val seen = mutableListOf<String>()
        var captured = 0
        val model = Model("Twice${System.nanoTime()}", autoCSVReports = false)
        val m = BModel(model)
        m.doctor.behavior {
            var local = 0
            repeat(3) { local++; captured++; delay(1.0) }
            seen.add("local=$local captured=$captured")
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        model.simulate()
        model.simulate()
        assertEquals(listOf("local=3 captured=3", "local=3 captured=6", "local=3 captured=9", "local=3 captured=12"), seen)
    }

    @Test
    @DisplayName("a behavior suspended at the end of a replication is terminated; nothing of it reaches the next")
    fun terminatedAtEndOfReplication() {
        val seen = mutableListOf<String>()
        val m = run(reps = 2, length = 25.0, declare = {
            doctor.behavior {
                try {
                    seen.add("start@$time")
                    delay(100.0)
                    seen.add("never")
                } finally {
                    seen.add("finally@$time")
                }
            }
        }, setup = { at(1.0) { seen.add("active=${doctor.isBehaviorActive} process=${doctor.behaviorProcess != null}") } })
        assertEquals(List(2) { listOf("start@0.0", "active=true process=true", "finally@25.0") }.flatten(), seen)
        assertFalse(m.doctor.isBehaviorActive)
        assertNull(m.doctor.behaviorProcess)
    }

    @Test
    @DisplayName("regression: a break every 120 for 15, five replications of 20 000 with a 5 000 warm-up")
    fun breakRegression() {
        var breaks = 0
        var offTime = 0.0
        val perRep = mutableListOf<Pair<Int, Double>>()
        val model = Model("Breaks${System.nanoTime()}", autoCSVReports = false)
        val m = BModel(model)
        m.doctor.cyclicBehavior {
            delay(120.0)
            m.doctor.goOffShift()
            val start = m.time
            delay(15.0)
            m.doctor.goOnShift()
            if (start >= 5000.0) { breaks++; offTime += m.time - start }
        }
        m.setups.add {
            if (breaks > 0 || offTime > 0.0) perRep.add(breaks to offTime)
            breaks = 0
            offTime = 0.0
        }
        model.numberOfReplications = 5
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 5000.0
        model.simulate()
        perRep.add(breaks to offTime)
        assertEquals(5, perRep.size)
        for ((b, off) in perRep) {
            assertTrue(abs(b - 15000.0 / 135.0) <= 1.0, "breaks per replication $b")
            assertTrue(abs(off / 15000.0 - 15.0 / 135.0) <= 0.005, "fraction of time off shift ${off / 15000.0}")
        }
    }

    // ── What a behavior can do ──────────────────────────────────────────────

    @Test
    @DisplayName("a behavior that seizes its own resource waits for the customer in service; customers queue behind it")
    fun selfSeize() {
        val m = run(length = 200.0, declare = {
            doctor.behavior {
                delay(120.0)
                val a = seize(doctor)
                log.add("break:start@$time")
                delay(15.0)
                release(a)
                log.add("break:end@$time")
            }
        }, setup = {
            customer("C", 0.0, hold = 130.0)
            customer("D", 125.0, hold = 1.0)
        })
        assertEquals(listOf("C:served@0.0", "break:start@130.0", "break:end@145.0", "D:served@145.0"), m.log)
    }

    @Test
    @DisplayName("a behavior can delay, seize another resource, wait for a signal, hold, activate, wait for a process and block until it completes")
    fun whatABehaviorCanDo() {
        val m = run(reps = 2, length = 100.0, declare = {
            doctor.behavior {
                delay(1.0)
                val a = seize(other); log.add("other@$time"); delay(1.0); release(a)
                waitFor(signal); log.add("signal@$time")
                hold(hq); log.add("hold@$time")
                val child = Actor("child") { delay(2.0); log.add("child@$time") }
                waitFor(child.p); log.add("waited@$time")
                val sibling = actor("sibling") { delay(3.0); log.add("sibling@$time") }
                blockUntilCompleted(sibling.p); log.add("blocked@$time")
            }
        }, setup = {
            at(5.0) { signal.signalAll() }
            at(6.0) { hq.removeAndResume(hq.peekNext()!!) }
        })
        val one = listOf("other@1.0", "signal@5.0", "hold@6.0", "child@8.0", "waited@8.0", "sibling@11.0", "blocked@11.0")
        assertEquals(one, m.log)
    }

    @Test
    @DisplayName("a behavior can use the timed and mixed waits")
    fun timedWaitsInABehavior() {
        val m = run(reps = 2, length = 100.0, declare = {
            doctor.cyclicBehavior {
                val r = seizeOrTimeout(doctor, timeout = 5.0)
                log.add("seize timedOut=${r.timedOut}@$time")
                r.valueOrNull()?.let { delay(10.0); release(it) }
                val s = waitForOrTimeout(signal, timeout = 3.0)
                log.add("signal timedOut=${s.timedOut}@$time")
                val w = waitForFirst(other.seizeTrigger(), signal.trigger(), timeout = 50.0)
                log.add("first=${if (w.timedOut) "timeout" else if (w.trigger === null) "?" else "ok"}@$time")
                w.valueOrNull(w.trigger as? WaitTrigger<Allocation> ?: return@cyclicBehavior)?.let { if (it is Allocation) release(it) }
                doctor.stopBehavior()
            }
        }, setup = {
            customer("C", 0.0, hold = 2.0)
            at(20.0) { signal.signalAll() }
        })
        // the behavior starts at time 0 ahead of the setup's customer, so it takes the doctor first
        assertEquals(listOf("seize timedOut=false@0.0", "C:served@10.0", "signal timedOut=true@13.0", "first=ok@13.0"), m.log)
    }

    @Test
    @DisplayName("another process interrupts the behavior's delay through behaviorProcess, and the behavior continues")
    fun interruptThroughBehaviorProcess() {
        val m = run(length = 100.0, declare = {
            doctor.behavior {
                delay(50.0, suspensionName = "long")
                log.add("behavior continues@$time")
            }
        }, setup = {
            actor("I") {
                delay(10.0)
                interruptDelay(doctor.behaviorProcess!!, "long", interruptTime = 5.0, postInterruptDelayTime = 1.0)
                log.add("interrupt done@$time")
            }
        })
        assertTrue(m.log.contains("interrupt done@15.0"), "${m.log}")
        assertTrue(m.log.any { it.startsWith("behavior continues@") }, "${m.log}")
    }

    @Test
    @DisplayName("a behavior on a resource with a capacity schedule runs")
    fun withCapacitySchedule() {
        val model = Model("Scheduled${System.nanoTime()}", autoCSVReports = false)
        val m = BModel(model)
        val schedule = CapacitySchedule(m, 0.0, repeatable = true, name = "S")
        schedule.addItem(capacity = 1, duration = 50.0)
        m.doctor.useSchedule(schedule, CapacityChangeRule.IGNORE)
        m.doctor.cyclicBehavior { delay(10.0); m.log.add("cycle@${m.time}") }
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        assertEquals(listOf("cycle@10.0", "cycle@20.0"), m.log)
    }

    // ── Misuse ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a second declaration, of either form in either order, and a declaration while running are refused")
    fun misuse() {
        for ((first, second) in listOf(true to true, true to false, false to true, false to false)) {
            val m = BModel(Model("Twice${System.nanoTime()}", autoCSVReports = false))
            if (first) m.doctor.cyclicBehavior { delay(1.0) } else m.doctor.behavior { delay(1.0) }
            val e = assertFailsWith<IllegalStateException> {
                if (second) m.doctor.cyclicBehavior { delay(1.0) } else m.doctor.behavior { delay(1.0) }
            }
            assertTrue(e.message!!.contains("at most one"), e.message)
        }
        var whileRunning: String? = null
        run(setup = { at(1.0) { whileRunning = runCatching { doctor.behavior { } }.exceptionOrNull()?.message } })
        assertTrue(whileRunning!!.contains("before the model runs"), whileRunning)
    }

    // ── Stopping and starting ───────────────────────────────────────────────

    /** A cyclic behavior that logs each cycle's start and end, with a finally, and a stop action. */
    private fun BModel.cycler() {
        doctor.cyclicBehavior {
            try {
                log.add("start@$time")
                delay(10.0)
                log.add("end@$time")
            } finally {
                log.add("finally@$time")
            }
        }
        doctor.onBehaviorStopped { log.add("stopped@$time") }
    }

    @Test
    @DisplayName("AFTER_CYCLE lets the current cycle finish and starts no other; the stop action runs once")
    fun stopAfterCycle() {
        val m = run(declare = { cycler() }, setup = { at(15.0) { doctor.stopBehavior() } })
        assertEquals(
            listOf("start@0.0", "end@10.0", "finally@10.0", "start@10.0", "end@20.0", "finally@20.0", "stopped@20.0"),
            m.log
        )
    }

    @Test
    @DisplayName("NOW stops mid-delay: the finally runs, the stop action runs once; not at the end of a replication")
    fun stopNow() {
        val m = run(declare = { cycler() }, setup = { at(15.0) { doctor.stopBehavior(BehaviorStop.NOW) } })
        assertEquals(listOf("start@0.0", "end@10.0", "finally@10.0", "start@10.0", "finally@15.0", "stopped@15.0"), m.log)
        val atEnd = run(length = 15.0, declare = { cycler() })
        assertFalse(atEnd.log.any { it.startsWith("stopped") }, "${atEnd.log}")
        val oneShot = run(declare = { doctor.behavior { delay(1.0) }; doctor.onBehaviorStopped { log.add("stopped") } })
        assertEquals(emptyList(), oneShot.log, "a one-shot block that returns does not run the stop action")
    }

    @Test
    @DisplayName("start after a stop: a fresh behavior at the current time; the misuse checks of start and stop")
    fun startAfterStop() {
        val errors = mutableListOf<String>()
        val m = run(declare = {
            doctor.cyclicBehavior {
                var cycle = 0
                cycle++
                log.add("cycle local=$cycle@$time")
                delay(10.0)
            }
        }, setup = {
            at(5.0) {
                errors.add(runCatching { doctor.startBehavior() }.exceptionOrNull()!!.message!!)
                doctor.stopBehavior(BehaviorStop.NOW)
                errors.add(runCatching { doctor.stopBehavior() }.exceptionOrNull()!!.message!!)
            }
            at(12.0) { doctor.startBehavior() }
            at(14.0) {
                doctor.stopBehavior()
                errors.add(runCatching { doctor.stopBehavior() }.exceptionOrNull()!!.message!!)
                doctor.stopBehavior(BehaviorStop.NOW)
                log.add("active=${doctor.isBehaviorActive}")
            }
        })
        assertEquals(listOf("cycle local=1@0.0", "cycle local=1@12.0", "active=false"), m.log)
        assertTrue(errors[0].contains("already running"), errors[0])
        assertTrue(errors[1].contains("not running"), errors[1])
        assertTrue(errors[2].contains("already pending"), errors[2])
    }

    @Test
    @DisplayName("a behavior cannot stop itself NOW, but can ask to stop after its current cycle")
    fun stopFromInside() {
        var error: String? = null
        val m = run(declare = {
            doctor.cyclicBehavior {
                log.add("cycle@$time")
                if (time >= 10.0) {
                    error = runCatching { doctor.stopBehavior(BehaviorStop.NOW) }.exceptionOrNull()?.message
                    doctor.stopBehavior()
                }
                delay(10.0)
            }
            doctor.onBehaviorStopped { log.add("stopped@$time") }
        })
        assertEquals(listOf("cycle@0.0", "cycle@10.0", "stopped@20.0"), m.log)
        assertTrue(error!!.contains("cannot stop itself"), error)
    }

    @Test
    @DisplayName("a stop at time 0 before the behavior's first statement cancels its start")
    fun stopBeforeStart() {
        val m = run(declare = { cycler() }, setup = { at(0.0, priority = KSLEvent.DEFAULT_PRIORITY - 1) { doctor.stopBehavior(BehaviorStop.NOW) } })
        assertEquals(listOf("stopped@0.0"), m.log)
    }

    @Test
    @DisplayName("behaviorActiveAtStart = false: not started; startBehavior starts it; as a control it changes an experiment's runs")
    fun activeAtStartControl() {
        val model = Model("Control${System.nanoTime()}", autoCSVReports = false)
        val m = BModel(model)
        m.doctor.cyclicBehavior { m.log.add("cycle@${m.time}"); delay(10.0) }
        m.setups.add { at(22.0) { if (!doctor.isBehaviorActive) doctor.startBehavior() } }
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        val control = assertNotNull(model.controls().control("Doctor.behaviorActiveAtStart"))
        control.value = 0.0
        model.simulate()
        assertEquals(listOf("cycle@22.0"), m.log)
        control.value = 1.0
        model.simulate()
        assertEquals(listOf("cycle@0.0", "cycle@10.0", "cycle@20.0"), m.log)
    }

    @Test
    @DisplayName("a stopped behavior's activated entities go on; the next replication starts by the setting, whatever happened before")
    fun stoppedBehaviorsEntitiesContinue() {
        val m = run(reps = 2, length = 30.0, declare = {
            doctor.behavior {
                actor("spawned") { delay(10.0); log.add("spawned done@$time") }
                delay(100.0)
            }
        }, setup = { at(5.0) { doctor.stopBehavior(BehaviorStop.NOW) } })
        assertEquals(listOf("spawned done@10.0"), m.log, "per replication; the log is cleared at each start")
    }

    @Test
    @DisplayName("stopBehavior(NOW) while waiting in its own queue, just woken for its own unit, holding it, and off shift on a break")
    fun stopNowInEachState() {
        // waiting in its own queue: the request leaves
        val waiting = run(declare = {
            doctor.behavior { delay(1.0); val a = seize(doctor); log.add("never"); release(a) }
        }, setup = {
            customer("C", 0.0, hold = 10.0)
            at(5.0) { doctor.stopBehavior(BehaviorStop.NOW); log.add("waiting=${doctor.myWaitingQ.size}") }
        })
        assertEquals(listOf("C:served@0.0", "waiting=0"), waiting.log)

        // woken for its own unit in the instant it is stopped: the promised unit goes to the next waiter
        val woken = run(declare = {
            doctor.behavior { delay(1.0); val a = seize(doctor); log.add("never"); release(a) }
        }, setup = {
            actor("C") {
                val a = seize(doctor)
                log.add("C:served@$time")
                delay(10.0)
                release(a)
                doctor.stopBehavior(BehaviorStop.NOW)
            }
            customer("D", 2.0, hold = 1.0)
        })
        assertEquals(listOf("C:served@0.0", "D:served@10.0"), woken.log)

        // holding its own unit: the unit is released and offered to the waiters
        val holding = run(declare = {
            doctor.behavior { val a = seize(doctor); delay(100.0); release(a) }
        }, setup = {
            customer("D", 1.0, hold = 1.0)
            at(5.0) { doctor.stopBehavior(BehaviorStop.NOW) }
        })
        assertEquals(listOf("D:served@5.0"), holding.log)

        // off shift on a break: the stop action puts the resource back on shift
        val onBreak = run(declare = {
            doctor.behavior { delay(1.0); doctor.goOffShift(); delay(15.0); doctor.goOnShift() }
            doctor.onBehaviorStopped { if (doctor.isOffShift) doctor.goOnShift() }
        }, setup = {
            customer("D", 2.0, hold = 1.0)
            at(5.0) { doctor.stopBehavior(BehaviorStop.NOW) }
        })
        assertEquals(listOf("D:served@5.0"), onBreak.log)
    }

    @Test
    @DisplayName("stopBehavior(NOW) while the behavior is in a timed or mixed wait: its registrations are withdrawn and nothing fires afterwards")
    fun stopNowInTimedWait() {
        val m = run(declare = {
            doctor.behavior {
                delay(1.0)
                val r = waitForFirst(other.seizeTrigger(), signal.trigger(), timeout = 10.0)
                log.add("never: ${r.timedOut}")
            }
        }, setup = {
            actor("X") { val a = seize(other); delay(20.0); release(a) }
            at(5.0) { doctor.stopBehavior(BehaviorStop.NOW) }
            at(6.0) { signal.signalAll(); log.add("inSignalQ=${signal.waitingQ.size} otherWaiting=${other.myWaitingQ.size}") }
        })
        assertEquals(listOf("inSignalQ=0 otherWaiting=0"), m.log)
    }

    @Test
    @DisplayName("stop and start from another process and from a scheduled event")
    fun stopAndStartFromAnywhere() {
        val m = run(declare = { doctor.cyclicBehavior { log.add("cycle@$time"); delay(10.0) } }, setup = {
            actor("controller") {
                delay(5.0)
                doctor.stopBehavior(BehaviorStop.NOW)
                delay(10.0)
                doctor.startBehavior()
            }
            at(18.0) { doctor.stopBehavior(BehaviorStop.NOW) }
            at(21.0) { doctor.startBehavior() }
        })
        assertEquals(listOf("cycle@0.0", "cycle@15.0", "cycle@21.0", "cycle@31.0", "cycle@41.0"), m.log)
    }

    @Test
    @DisplayName("a behavior running through the warm-up is not restarted")
    fun throughWarmUp() {
        val m = run(length = 25.0, warmUp = 15.0, declare = { doctor.cyclicBehavior { log.add("cycle@$time"); delay(10.0) } })
        assertEquals(listOf("cycle@0.0", "cycle@10.0", "cycle@20.0"), m.log)
    }

    // ── The zero-time guard ─────────────────────────────────────────────────

    @Test
    @DisplayName("a cycle that never waits throws, naming the resource; a smaller limit takes effect; a bounded burst passes")
    fun zeroTimeGuard() {
        var busy = false
        val e = assertFailsWith<Exception> {
            run(declare = { doctor.cyclicBehavior { if (busy) delay(10.0) } })
        }
        val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(message.contains("of Doctor ") && message.contains("never waits"), message)

        var count = 0
        val small = assertFailsWith<Exception> {
            run(declare = {
                doctor.maxCyclesPerInstant = 5
                doctor.cyclicBehavior { count++; if (count > 8) delay(10.0) }
            })
        }
        assertTrue(generateSequence<Throwable>(small) { it.cause }.any { it.message?.contains("6 cycles") == true })

        var burst = 0
        val ok = run(declare = {
            doctor.maxCyclesPerInstant = 10
            doctor.cyclicBehavior { burst++; if (burst % 8 == 0) delay(10.0) }
        })
        assertEquals(emptyList(), ok.log)
        assertTrue(burst > 8)
    }

    // ── The trace ───────────────────────────────────────────────────────────

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: the behavior's entity is created as ResourceBehavior, a restart is a new one, and the trace reads back")
    fun trace() {
        val sink = CollectingSink()
        run(sink = sink, declare = { doctor.cyclicBehavior { val a = seize(doctor); delay(10.0); release(a) } }, setup = {
            at(5.0) { doctor.stopBehavior(BehaviorStop.NOW) }
            at(7.0) { doctor.startBehavior() }
        })
        val created = sink.events.filterIsInstance<AnimationEvent.EntityCreated>().filter { it.entityType == "ResourceBehavior" }
        assertEquals(2, created.size, "one for the start and one for the restart")
        val trace = Files.createTempFile("behavior", ".atf")
        try {
            Files.writeString(trace, AnimationTraceHeader().encodeToLine() + "\n" +
                    sink.events.joinToString("\n") { AnimationEvent.encodeToLine(it) } + "\n")
            val (_, events) = TraceFileReader.readAll(trace)
            assertEquals(sink.events, events)
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
