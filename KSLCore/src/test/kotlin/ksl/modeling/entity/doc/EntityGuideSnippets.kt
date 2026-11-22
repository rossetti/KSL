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
@file:Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused", "UNUSED_VALUE", "RedundantSuspendModifier")

/*
 *  The compiled host for every Kotlin block of `docs/guides/ksl-entity.md`. Each block appears here verbatim,
 *  inside just enough of a model to compile; `EntityGuideSnippetsTest` checks that every block of the guide
 *  is here, so a guide block that no longer compiles fails the build.
 *
 *  Nothing here is run. The names a fragment uses without declaring (`doctors`, `giveUp`, …) are declared
 *  around it, with the simplest body that compiles.
 */
// §3 Quick start is the first block: its imports run straight into its class, as on the page.

package ksl.modeling.entity.doc

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.receiveMessageOrTimeout
import ksl.modeling.entity.Allocation
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.DelayResult
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.Signal
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.utilities.random.rvariable.ConstantRV
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class Pharmacy(parent: ModelElement, name: String? = null) :
    ProcessModel(parent, name) {

    private val pharmacist = ResourceWithQ(this, "Pharmacist", capacity = 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(0.5, 2))
    private val timeBetweenArrivals = ExponentialRV(1.0, 1)
    private val timeInSystem = Response(this, "TimeInSystem")

    private inner class Customer : Entity() {
        val pharmacyProcess: KSLProcess = process(isDefaultProcess = true) {
            timeStamp = time
            val a = seize(pharmacist)
            delay(serviceTime)
            release(a)
            timeInSystem.value = time - timeStamp
        }
    }

    private val generator =
        EntityGenerator(::Customer, timeBetweenArrivals, timeBetweenArrivals)
}

fun main() {
    val m = Model()
    Pharmacy(m, "Pharmacy")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20_000.0
    m.lengthOfReplicationWarmUp = 5_000.0
    m.simulate()
    m.print()
}

// ── §4 build a minimal process model; generate entities; use a Resource ───────────────────────────────

class HelloWorld(parent: ModelElement) : ProcessModel(parent) {
    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            delay(5.0)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(2.0, 1), ExponentialRV(2.0, 1))
}

class GeneratorPatterns(parent: ModelElement) : ProcessModel(parent) {
    private inner class Job : Entity() {
        val default: KSLProcess = process(isDefaultProcess = true) { delay(1.0) }
    }

    // (a) inter-arrival-time-based generation
    private val byTime = EntityGenerator(::Job, ExponentialRV(2.0, 1), ExponentialRV(2.0, 1))

    // (b) count-based generation: activate after every N counter increments
    private val byCount = ProcessActivator(::Job, initialCountLimit = 5)

    // (c) explicit activation from an event callback
    override fun initialize() {
        super.initialize()
        schedule(this::startSomething, 1.0)
    }
    private fun startSomething(e: KSLEvent<Nothing>) {
        val j = Job()
        activate(j.default)
    }
}

class ResourcePatterns(parent: ModelElement) : ProcessModel(parent) {
    private val r1 = ResourceWithQ(this, "R1", capacity = 1)
    private val r2 = ResourceWithQ(this, "R2", capacity = 2)
    private val st = RandomVariable(this, ExponentialRV(1.0, 2))

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            // (a) explicit seize and release with the allocation receipt
            val a = seize(r1)
            delay(st)
            release(a)

            // (b) the use(...) shorthand — seize, delay, release in one call
            use(r1, delayDuration = st)

            // (c) multi-unit seize
            val twoUnits = seize(r2, amountNeeded = 2)
            delay(st)
            release(twoUnits)
        }
    }
}

class WithBundledQueue(parent: ModelElement) : ProcessModel(parent) {
    private val server = ResourceWithQ(this, "Server", capacity = 1)
    private val service = RandomVariable(this, ExponentialRV(0.7, 2))

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            use(server, delayDuration = service)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}

class PoolModel(parent: ModelElement) : ProcessModel(parent) {
    private val r1 = Resource(this, "R1", capacity = 1)
    private val r2 = Resource(this, "R2", capacity = 1)
    private val r3 = Resource(this, "R3", capacity = 1)

    // Pool with its own bundled queue.
    private val pool = ResourcePoolWithQ(this, listOf(r1, r2, r3), name = "Pool")
    private val service = RandomVariable(this, ExponentialRV(0.5, 2))

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(pool)
            delay(service)
            release(a)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}

// ── §4 renege ─────────────────────────────────────────────────────────────────────────────────────────

private class Reneging(parent: ModelElement) : ProcessModel(parent) {
    private val doctors = ResourceWithQ(this, "Doctors", capacity = 2)
    private val examTime = RandomVariable(this, ExponentialRV(20.0, 2))
    private val leftWithoutBeingSeen = Counter(this, "LeftWithoutBeingSeen")

    private inner class Patient : Entity() {
        val visit: KSLProcess = process(isDefaultProcess = true) {
            val r = seizeOrTimeout(doctors, timeout = 120.0)
            if (r.timedOut) {
                leftWithoutBeingSeen.increment()      // count reneges yourself
                return@process
            }
            val a = r.value
            delay(examTime)
            release(a)
        }
    }
}

// ── §4 add a shift / capacity schedule ────────────────────────────────────────────────────────────────

class ShiftedModel(parent: ModelElement) : ProcessModel(parent) {
    private val server = ResourceWithQ(this, "Server", capacity = 1)
    private val service = RandomVariable(this, ExponentialRV(0.7, 2))

    init {
        val schedule = CapacitySchedule(
            parent = this,
            startTime = 0.0,
            autoStartOption = true,
            repeatable = true,
        )
        schedule.addItem(capacity = 1, duration = 480.0)  // on shift
        schedule.addItem(capacity = 0, duration = 480.0)  // off shift
        server.useSchedule(schedule, CapacityChangeRule.WAIT)
    }

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            use(server, delayDuration = service)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}

// ── §4 give a resource its own behavior ───────────────────────────────────────────────────────────────

private class BreakThatCutsIn(parent: ModelElement) : ProcessModel(parent) {
    val doctor = ResourceWithQ(this, "Doctor")
    init {
        doctor.cyclicBehavior {
            delay(120.0)
            doctor.goOffShift()
            delay(15.0)
            doctor.goOnShift()
        }
        doctor.onBehaviorStopped { if (doctor.isOffShift) doctor.goOnShift() }
    }
}

private class BreakThatWaits(parent: ModelElement) : ProcessModel(parent) {
    private val doctor = ResourceWithQ(this, "Doctor")

    init {
        doctor.cyclicBehavior {
            delay(120.0)
            val a = seize(doctor)
            delay(15.0)
            release(a)
        }
    }
}

// ── §4 fail and repair a resource ─────────────────────────────────────────────────────────────────────

private class Failing(parent: ModelElement) : ProcessModel(parent) {
    val machine = ResourceWithQ(this, "Machine")
    init {
        machine.interruptRule = InterruptRule.RESUME
        machine.failures(
            timeBetween = ExponentialRV(120.0, 3),
            repairTime = ExponentialRV(10.0, 4),
            basis = ResourceFailureBasis.BUSY_TIME
        )
    }
}

// ── §4 let urgent work take a resource (preemption) ───────────────────────────────────────────────────

private class Preempting(parent: ModelElement) : ProcessModel(parent) {
    private val doctor = ResourceWithQ(this, "Doctor")
    private val treatment = RandomVariable(this, ExponentialRV(30.0, 2))

    private inner class Emergency : Entity() {
        init { priority = 1 }
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = preempt(doctor)          // or preempt(doctor, rule = InterruptRule.RESTART)
            delay(treatment)
            release(a)
        }
    }
}

// ── §4 hold a resource across work that does not use it; react to a failure or preemption ─────────────

/**
 *  Runnable as well as compiled: `ReactingPatternTest` runs the patient's treatment loop, the guide's example,
 *  through failures and preemptions, with a constant [treatment].
 */
internal class HoldingAndReacting(
    parent: ModelElement,
    treatment: ksl.utilities.random.rvariable.RVariableIfc = ExponentialRV(30.0, 3)
) : ProcessModel(parent) {
    private val loadingBay = ResourceWithQ(this, "LoadingBay")
    private val forklift = ResourceWithQ(this, "Forklift")
    private val loadTime = RandomVariable(this, ExponentialRV(10.0, 2))
    internal val rooms = ResourceWithQ(this, "Rooms", capacity = 4)
    internal val doctors = ResourceWithQ(this, "Doctors", capacity = 2)
    internal val otherDoctors = ResourceWithQ(this, "OtherDoctors", capacity = 2)
    private val treatmentRV = RandomVariable(this, treatment)
    private val calledAway = Signal(this, "CalledAway")

    /** When each patient finished its treatment, and who treated it. */
    internal val finished = mutableListOf<String>()

    /** What a test sets up at the start of each replication. */
    internal val setups = mutableListOf<HoldingAndReacting.() -> Unit>()

    /** Admits a patient at time 0, of priority [prio], whose treatment loops, or reacts only once if [once]. */
    internal fun admit(prio: Int = 9, once: Boolean = false) {
        val patient = Patient(prio)
        activate(if (once) patient.treatReactingOnce else patient.treat)
    }

    /** Runs [action] at time [t], at event priority [priority]. */
    internal fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
        schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
    }

    /**
     *  An urgent entity of priority 1 that preempts [n] units of [res] at [from] under [rule], holds them for
     *  [hold], and logs, just before it preempts, whether the first doctor's holder is missing units.
     */
    internal inner class Urgent(
        val res: ResourceWithQ, val n: Int, val from: Double, val hold: Double, val rule: InterruptRule,
        val delayPriority: Int, val seizePriority: Int, val log: MutableList<String>
    ) : Entity() {
        init {
            priority = 1
        }

        val p: KSLProcess = process {
            delay(from, delayPriority = delayPriority)
            log.add("P checks@$time missing=${doctors.allocations().first().hasMissingUnits}")
            val a = preempt(res, n, seizePriority = seizePriority, rule = rule)
            log.add("P got@$time")
            delay(hold)
            release(a)
        }
    }

    /** Activates an [Urgent] entity. */
    internal fun urgent(
        res: ResourceWithQ, n: Int, from: Double, hold: Double, rule: InterruptRule,
        delayPriority: Int, seizePriority: Int, log: MutableList<String>
    ) {
        activate(Urgent(res, n, from, hold, rule, delayPriority, seizePriority, log).p)
    }

    override fun initialize() {
        finished.clear()
        for (setup in setups) setup()
    }

    private inner class Truck : Entity() {
        val load: KSLProcess = process {
            val bay = seize(loadingBay)
            val truck = seize(forklift)
            delay(loadTime, uses = listOf(truck))   // a bay failure does not stop the loading
            release(truck)
            release(bay)
        }
    }

    internal inner class Patient(prio: Int = 1) : Entity() {
        init {
            priority = prio
        }

        val treat: KSLProcess = process {
            val room = seize(rooms)
            var doc  = seize(doctors)
            val work = treatmentRV.value
            var left = work
            while (true) {
                val r = delayOrInterrupt(left, uses = listOf(doc, room))
                if (r is DelayResult.Completed) break
                r as DelayResult.Interrupted
                // go on with what was left, or start again, as the rule of the taking says
                left = if (r.rule == InterruptRule.RESTART) work else r.timeRemaining
                // wait up to 15 minutes for what was taken to come back; if it is the doctor, see another one
                val back = waitForFirst(r.by.restoredTrigger(), timeout = 15.0)
                if (back.timedOut && r.by === doc) { release(doc); doc = seize(otherDoctors) }
            }
            release(doc); release(room)
            finished.add("finished@$time by ${doc.myResource.name}")
        }

        val treatReactingOnce: KSLProcess = process {
            val room = seize(rooms)
            var doc  = seize(doctors)
            val work = treatmentRV.value
            val r = delayOrInterrupt(work, uses = listOf(doc, room))
            if (r is DelayResult.Interrupted) {
                // wait up to 15 minutes for what was taken to come back; if it is the doctor, see another one
                val back = waitForFirst(r.by.restoredTrigger(), timeout = 15.0)
                if (back.timedOut && r.by === doc) { release(doc); doc = seize(otherDoctors) }
                // the rest of the work, held through any later interruption until it is done
                delay(if (r.rule == InterruptRule.RESTART) work else r.timeRemaining, uses = listOf(doc, room))
            }
            release(doc); release(room)
            finished.add("finished@$time by ${doc.myResource.name}")
        }

        val treatAsAWait: KSLProcess = process {
            val doc = seize(doctors)
            val treatment = treatmentRV.value
            val w = waitForFirst(doc.interruptedTrigger(), calledAway.trigger(), timeout = treatment)
            release(doc)
        }
    }
}

// ── §4 run two stations in tandem ─────────────────────────────────────────────────────────────────────

class TandemQ(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {
    private val worker1 = ResourceWithQ(this, "worker1")
    private val worker2 = ResourceWithQ(this, "worker2")
    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    private val wip = TWResponse(this, "NumInSystem")
    private val timeInSystem = Response(this, "TimeInSystem")

    private inner class Customer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            use(worker1, delayDuration = st1)
            use(worker2, delayDuration = st2)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
    private val gen = EntityGenerator(::Customer, ExponentialRV(2.0, 1), ExponentialRV(2.0, 1))
}

// ── §4 coordinate producer and consumer with BlockingQueue ────────────────────────────────────────────

class ProducerConsumer(parent: ModelElement) : ProcessModel(parent) {
    private val buffer: BlockingQueue<Item> = BlockingQueue(this, capacity = 5, name = "Buffer")

    private inner class Item : Entity()

    private inner class Producer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                delay(1.0)
                val item = Item()
                send(item, buffer)               // blocks if buffer is full
            }
        }
    }

    private inner class Consumer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val items = buffer.waitFor(amount = 1)   // blocks if buffer is empty
                delay(2.0)
            }
        }
    }

    init {
        activate(Producer().script)
        activate(Consumer().script)
    }
}

private class PerishableBuffer(parent: ModelElement) : ProcessModel(parent) {
    private inner class Perishable(private val expires: Double) : Entity() {
        val isExpired: Boolean get() = time >= expires
    }

    private val buffer: BlockingQueue<Perishable> = BlockingQueue(this, name = "Perishables")

    fun purge() {
        buffer.removeAllFromChannel(buffer.availableForRemoval { it.isExpired })
    }
}

// ── §4 wait with HoldQueue and Signal ─────────────────────────────────────────────────────────────────

class HoldAndSignal(parent: ModelElement) : ProcessModel(parent) {
    private val parking = HoldQueue(this, "Parking")
    private val greenLight = Signal(this, "GreenLight")

    private inner class Car : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            hold(parking)                         // wait until programmatically released
            waitFor(greenLight)                   // wait until signaled
            delay(1.0)
        }
    }

    fun releaseFirstCar() {
        val first = parking.peekNext()
        if (first != null) parking.removeAndResume(first)
    }

    fun greenLightAll() {
        greenLight.signalAll()
    }
}

// ── §4 wait with a deadline; wait for whichever comes first ───────────────────────────────────────────

private class Deadlines(parent: ModelElement) : AgentModel(parent, "Deadlines") {
    private inner class Part : Entity()

    private val bin: BlockingQueue<Part> = BlockingQueue(this, name = "Bin")
    private val greenLight = Signal(this, "GreenLight")
    private val alarmSignal = Signal(this, "Alarm")
    private val beds = ResourceWithQ(this, "Beds", capacity = 10)
    private val deteriorated = Signal(this, "Deteriorated")

    private fun giveUp() {}
    private fun handle(message: AgentMessage) {}
    private fun evacuate() {}
    private fun assemble(parts: List<Part>) {}
    private fun admit(bed: Allocation) {}
    private fun leaveHospital() {}
    private fun transferToICU() {}

    private inner class Assembler : Agent("Assembler") {
        val convId = "order-1"

        val shorthands: KSLProcess = process {
            val callBack = Suspension("callBack")

            val reply = receiveMessageOrTimeout(mailbox, timeout = 5.0)    // ksl.modeling.agent
            if (reply.timedOut) giveUp() else handle(reply.value)

            val parts = waitForItemsOrTimeout(bin, timeout = 10.0, amount = 2)
            val got = parts.valueOrNull() ?: emptyList()

            val lit = waitForOrTimeout(greenLight, timeout = 30.0)
            val answered = suspendForOrTimeout(callBack, timeout = 3.0)
        }

        val firstOfThree: KSLProcess = process {
            val reply = mailbox.trigger { it.conversationId == convId }
            val alarm = alarmSignal.trigger()
            val part = bin.itemsTrigger(amount = 1)

            val r = waitForFirst(reply, alarm, part, timeout = 60.0)
            when (r.trigger) {
                reply -> handle(r.valueOf(reply))          // an AgentMessage
                alarm -> evacuate()
                part -> assemble(r.valueOf(part))          // a List of items
                null -> giveUp()                           // timed out
            }
        }

        val bedOrWorse: KSLProcess = process {
            val bed = beds.seizeTrigger()
            val r = waitForFirst(bed, deteriorated.trigger(), timeout = 240.0)
            when (r.trigger) {
                bed  -> admit(r.valueOf(bed))     // the allocation; release it as usual
                null -> leaveHospital()
                else -> transferToICU()
            }
        }
    }
}

// ── §4 move an entity in space; convey items on a conveyor ────────────────────────────────────────────

class MovingResource(parent: ModelElement, dock: LocationIfc, machine: LocationIfc) :
    ProcessModel(parent) {

    private val forklift: MovableResourceWithQ =
        MovableResourceWithQ(this, initLocation = dock, defaultVelocity = ConstantRV(1.0))

    private inner class Job(val from: LocationIfc, val to: LocationIfc) : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(forklift)
            move(forklift, toLoc = from)         // empty travel to pickup
            move(forklift, toLoc = to)           // loaded travel to drop-off
            release(a)
        }
    }
}

class ConveyedModel(parent: ModelElement) : ProcessModel(parent) {
    private val arrivalArea = "ArrivalArea"
    private val exitArea = "ExitArea"

    private val conveyor: Conveyor = Conveyor.builder(this, "Conveyor")
        .conveyorType(Conveyor.Type.NON_ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .maxCellsAllowed(1)
        .firstSegment(arrivalArea, exitArea, 100)
        .build()

    private val pack = RandomVariable(this, ExponentialRV(2.0, 2))

    private inner class Part : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val req = requestConveyor(conveyor, arrivalArea, numCellsNeeded = 1)
            rideConveyor(req, exitArea)
            delay(pack)
            exitConveyor(req)
        }
    }
    private val gen = EntityGenerator(::Part, ExponentialRV(10.0, 1), ExponentialRV(10.0, 1))
}

// ── §4 interrupt a delay; declare multiple processes on one entity ────────────────────────────────────

private class Interrupting(parent: ModelElement) : ProcessModel(parent) {
    private val repairTime = RandomVariable(this, ExponentialRV(0.5, 3))

    private inner class Machine : Entity() {
        val script: KSLProcess = process { delay(10.0, suspensionName = "work") }
    }

    private val machine = Machine()

    private inner class Repairer : Entity() {
        val script: KSLProcess = process {
            if (machine.script.isInDelay("work")) {
                interruptDelayAndContinue(machine.script, "work", repairTime)
            } else {
                delay(repairTime)   // the machine was idle: down for the repair anyway
            }
        }
    }
}

class NamedProcesses(parent: ModelElement) : ProcessModel(parent) {
    private val server = ResourceWithQ(this, "Server")
    private val st = RandomVariable(this, ExponentialRV(1.0, 2))
    private val repairTime = RandomVariable(this, ExponentialRV(0.5, 3))

    private inner class Job : Entity() {
        val mainProcess: KSLProcess = process(processName = "Main", isDefaultProcess = true) {
            use(server, delayDuration = st)
        }
        val repairSubProcess: KSLProcess = process(processName = "Repair") {
            delay(repairTime)
        }
    }
}

// ── §4 know what runs at the end of a replication ────────────────────────────────────────────────────

class EndingHooks(parent: ModelElement) : ProcessModel(parent) {
    private inner class Recovery : Entity() {
        val script: KSLProcess = process { delay(1.0) }
    }

    private inner class Job : Entity() {
        override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
            if (isReplicationEnding) return
            activate(Recovery().script)
        }
    }
}

// ── §4 read results ───────────────────────────────────────────────────────────────────────────────────

fun readResults(server: ResourceWithQ, buffer: BlockingQueue<*>) {
    val avgBusy = server.numBusyUnits.acrossReplicationStatistic.average
    val avgQ = server.waitingQ.timeInQ.acrossReplicationStatistic.average
    val avgWaitInBuffer = buffer.requestQ.timeInQ.acrossReplicationStatistic.average
}
