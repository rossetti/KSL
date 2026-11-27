# Waiting with a deadline, or for whichever comes first — a tutorial through the examples

*Eight worked cases.* Each one states a **problem**, describes the **system** with a table of every
distribution and parameter, draws the **model**, gives **the whole of its code** with an explanation of
every part, shows what it **produced**, and says **how we know it is right**: each example is checked
against an answer found some other way, either the exact result of a queueing model or the same system
written another way. Every number on this page came from running the example named beside it.

> **Status: experimental.** Waits with a time limit (`seizeOrTimeout`, `waitForOrTimeout`,
> `waitForItemsOrTimeout`, `suspendForOrTimeout`, `receiveMessageOrTimeout`) and waits for the first of
> several things (`waitForFirst` and its triggers) are released as experimental until the textbook covers
> them. Their API may change in a future release.

This page assumes you can read a process model: an entity, its `process { … }`, `seize`, `delay` and
`release`, as in the textbook's chapter 6. The reference for every function used here is
[`ksl-entity`](ksl-entity.md), sections "...renege", "...wait with a deadline" and "...wait for whichever
comes first".

---

## How to read this tutorial

A process waits in many places: for a resource, for a signal, for items in a blocking queue, for a
message, for another process to resume it. Without a time limit each of those waits is open-ended: a plain wait cannot
say *wait for at most 15 minutes*, nor *wait for this or that, whichever happens first*, unless the process
schedules events of its own to find and remove itself. The constructs here say it directly. The cases below show both kinds of wait, one
construct at a time, and then together.

| # | Case | What it teaches | Run it with |
|---|---|---|---|
| 1 | [Impatient callers](#1-impatient-callers) | a seize with a time limit; counting those who give up; Little's law | `:KSLExamples:impatientCallers` |
| 2 | [The walk-in clinic, two ways](#2-the-walk-in-clinic-two-ways) | the textbook's reneging, written as one call | `:KSLExamples:walkInClinicTwoWays` |
| 3 | [First free of two](#3-first-free-of-two) | waiting for whichever of two resources is free first | `:KSLExamples:firstFreeOfTwo` |
| 4 | [The shuttle that leaves full or on time](#4-the-shuttle-that-leaves-full-or-on-time) | two parties, each waiting for the other with a deadline | `:KSLExamples:shuttleStop` |
| 5 | [Kitting, with expediting](#5-kitting-with-expediting) | three things in one wait; ties at one instant | `:KSLExamples:kittingWithExpediting` |
| 6 | [Beds from a pool, with a fallback](#6-beds-from-a-pool-with-a-fallback) | a timed seize of a pool; waiting in two pools at once | `:KSLExamples:hospitalBeds` |
| 7 | [Two colours in one bin](#7-two-colours-in-one-bin) | two item triggers on one blocking queue; the request selector; taking every match | `:KSLExamples:twoColourBin` |
| 8 | [Timed auctions](#8-timed-auctions) | an agent's message and an agent's signal in one wait; a timed receive | `:KSLExamples:timedAuction` |

The examples are in `KSLExamples/src/main/kotlin/ksl/examples/general/processview/waits/`. Each has a
`main`; from IntelliJ, run it with the green arrow beside it, or from the command line with the Gradle task
in the table.

**Reading the code.** Each case gives its example **in full**, apart from the license header, the import
list and the file's own documentation comments, which this page replaces. The code is split into numbered
parts, each followed by what it does and why. Case 2 is the exception: most of its model is the textbook's
clinic, line for line, so only the lines that differ are shown.

**Reading the numbers.** Every model here runs several replications from fixed random number streams, so
running an example gives exactly the numbers on this page. Where an estimate is compared with an exact
value, the comparison is a 99% confidence interval from the replications, and the question is whether the
exact value lies inside it. Where two formulations of one system are compared, they draw the same random
numbers in the same order, and the question is whether every result is *identical*: not close, the same.
A half-width shown beside an estimate is of its 95% confidence interval.

`WaitsExamplesValidationTest` in `KSLExamples/src/test` repeats every one of these checks on each build.

---

## 1. Impatient callers

### The problem

A call centre answers calls with a fixed team of agents. When every agent is busy, a caller waits on hold,
but not for ever: each caller has a limit, and a caller who reaches it hangs up. The centre wants to know
what fraction of its callers it loses this way, and how long callers wait.

### The system

Calls arrive at random, one every 25 seconds on average. Ten agents answer them in the order they
arrived. A call lasts four minutes on average. Each caller's patience, the longest they will wait on
hold, is random, three minutes on average.

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between calls | exponential, mean 1/2.4 minutes (2.4 calls a minute) | `timeBetweenCalls` |
| Number of agents | 10 | `Agents` |
| Call length | exponential, mean 4 minutes | `ServiceTime` |
| Patience | exponential, mean 3 minutes | `Patience` |
| Replications | 20 of 20,000 minutes, after a warm-up of 2,000 | |

With every time exponential, this is the M/M/c+M queue, also called Erlang-A, and its long-run answers are
known exactly. That is what makes it a good first case: the simulation can be checked against them.

### The model

```mermaid
flowchart LR
    A(["call arrives"]) --> W{"an agent free<br/>before patience runs out?"}
    W -- yes --> S["talk with the agent"] --> D(["call ends"])
    W -- no --> H(["caller hangs up"])
```

*Figure 1 — a caller's process. The decision is one call, `seizeOrTimeout`: it waits for an agent, but for
no longer than the caller's patience.*

### The code

`ImpatientCallers.kt` in full.

#### 1. The model's parameters and its elements

```kotlin
class CallCenter(
    parent: ModelElement,
    numAgents: Int = 10,
    val arrivalRate: Double = 2.4,
    meanServiceTime: Double = 4.0,
    meanPatience: Double = 3.0,
    recordRenegedWait: Boolean = true,
    name: String? = null
) : ProcessModel(parent, name) {

    private val agents = ResourceWithQ(this, "Agents", capacity = numAgents)
    private val timeBetweenCalls = ExponentialRV(1.0 / arrivalRate, 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(meanServiceTime, 2), "ServiceTime")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")
    private val recordAbandonedWaits = recordRenegedWait

    private val abandoned = Response(this, "ProbAbandon")
    private val numAbandoned = Counter(this, "NumAbandoned")
    private val numServed = Counter(this, "NumServed")

    /** The proportion of callers who hang up. */
    val probAbandon get() = abandoned
    /** The time-average number of callers waiting. */
    val numWaiting get() = agents.waitingQ.numInQ
    /** The time callers wait, for every caller or only those answered: see [recordRenegedWait]. */
    val timeWaiting get() = agents.waitingQ.timeInQ
```

The constructor takes every parameter of the table above as a default, so `CallCenter(model)` builds the
system as described, and a study can change any of them. `recordRenegedWait` is the subject of part 4.

`Agents` is a `ResourceWithQ`: a resource with its own queue, where callers wait. The three statistics
are a `Response` whose observations are 1 for a caller who hangs up and 0 for one who is answered, so its
average is the proportion who hang up, and two counters. The properties at the end expose what the
example's `main` and its test need to read.

#### 2. The caller

```kotlin
    private inner class Caller : Entity() {
        val call: KSLProcess = process(isDefaultProcess = true) {
            val result = seizeOrTimeout(
                agents,
                timeout = patience.value,
                recordRenegedWait = recordAbandonedWaits
            )
            if (result.timedOut) {
                abandoned.value = 1.0
                numAbandoned.increment()
                return@process
            }
            abandoned.value = 0.0
            delay(serviceTime)
            release(result.value)
            numServed.increment()
        }
    }

    private val generator = EntityGenerator(::Caller, timeBetweenCalls, timeBetweenCalls)
}
```

This is the whole of the new construct. `seizeOrTimeout` seizes as `seize` does: the caller's request
joins the agents' queue and is served in the same order. The difference is `timeout`: if no agent has been
given to the caller by then, the request leaves the queue, nothing is allocated, and the call returns
**timed out**. The timeout here is a fresh draw of `patience` for each caller.

The result is a `WaitResult`. `result.timedOut` says which way the wait ended. If it did not time out,
`result.value` is the allocation, released as any other. There is no built-in count of callers who give
up: the model counts them from `timedOut`, as here.

What is *not* here matters as much. In the textbook's style of reneging (case 2), the caller would schedule
an event for the end of its patience, and the event would find the caller's request in the queue and
remove it. Here there is no event to write and no request to find.

#### 3. The exact answers

```kotlin
class ErlangA(val lambda: Double, val mu: Double, val theta: Double, val c: Int) {

    /** The mean number of customers waiting. */
    val meanNumberWaiting: Double

    init {
        require(lambda > 0.0 && mu > 0.0 && theta > 0.0 && c >= 1) { "rates must be > 0 and c >= 1" }
        // p(n) is proportional to the product of birth over death rates up to n; stop when it is negligible
        var term = 1.0
        var total = 1.0
        var waiting = 0.0
        var n = 0
        while (true) {
            n++
            term *= lambda / (minOf(n, c) * mu + maxOf(n - c, 0) * theta)
            total += term
            waiting += maxOf(n - c, 0) * term
            if (n > c && term < 1.0e-16 * total) break
        }
        meanNumberWaiting = waiting / total
    }

    /** The probability that a customer abandons. */
    val probAbandon: Double get() = theta * meanNumberWaiting / lambda

    /** The mean time in queue of all customers, those who abandon included. */
    val meanWait: Double get() = meanNumberWaiting / lambda
}
```

Count the callers in the system, waiting or talking. A new caller adds one, at rate λ = 2.4 a minute. With
`n` callers in the system, `min(n, c)` are talking, each finishing at rate μ = 1/4, and `max(n − c, 0)` are
waiting, each hanging up at rate θ = 1/3. So the count is a birth-death process, and its long-run
distribution is the product of birth over death rates, normalized. `ErlangA` sums that product until the
terms are negligible, and from it gets the mean number waiting, `Lq`.

Two more answers follow from `Lq`. Waiting callers hang up at total rate `θ Lq`, so the fraction of callers
who hang up is `θ Lq / λ`. And by Little's law, the mean time on hold of *all* callers, those who hang up
included, is `Lq / λ`.

#### 4. The study

```kotlin
fun main() {
    val exact = ErlangA(lambda = 2.4, mu = 1.0 / 4.0, theta = 1.0 / 3.0, c = 10)

    val model = Model("ImpatientCallers", autoCSVReports = false)
    val center = CallCenter(model, name = "CallCenter")
    model.numberOfReplications = 20
    model.lengthOfReplication = 20_000.0
    model.lengthOfReplicationWarmUp = 2_000.0
    model.simulate()

    println("The call centre against the M/M/c+M (Erlang-A) answers, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compare("probability of abandoning", center.probAbandon.acrossReplicationStatistic, exact.probAbandon)
    compare("mean number waiting", center.numWaiting.acrossReplicationStatistic, exact.meanNumberWaiting)
    compare("mean wait, all callers", center.timeWaiting.acrossReplicationStatistic, exact.meanWait)

    val notRecorded = Model("ImpatientCallersWaitsOfServedOnly", autoCSVReports = false)
    val centerServedOnly = CallCenter(notRecorded, recordRenegedWait = false, name = "CallCenter")
    notRecorded.numberOfReplications = 20
    notRecorded.lengthOfReplication = 20_000.0
    notRecorded.lengthOfReplicationWarmUp = 2_000.0
    notRecorded.simulate()

    println()
    println("Little's law, L = lambda W, with lambda = ${center.arrivalRate} calls per minute")
    println(String.format("%-36s %10s %12s", "abandoned waits", "L", "lambda W"))
    littlesLaw("recorded (the default)", center)
    littlesLaw("not recorded", centerServedOnly)
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 1.")
}
```

The first run sets each estimate beside its exact value. The second run is the same centre with
`recordRenegedWait = false`, which leaves the waits of callers who hung up out of the queue's time in
queue. The centre is otherwise identical, so its number waiting is identical too. Little's law, L = λW,
is then checked for both.

#### 5. Printing

```kotlin
private fun compare(label: String, stat: ksl.utilities.statistic.StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}

private fun littlesLaw(label: String, center: CallCenter) {
    val l = center.numWaiting.acrossReplicationStatistic.average
    val lambdaW = center.arrivalRate * center.timeWaiting.acrossReplicationStatistic.average
    println(String.format("%-36s %10.4f %12.4f", label, l, lambdaW))
}
```

### What it shows

```text
The call centre against the M/M/c+M (Erlang-A) answers, 99% confidence intervals
quantity                       estimate               interval      exact   inside
probability of abandoning        0.1174       [0.1153, 0.1196]     0.1174      yes
mean number waiting              0.8497       [0.8326, 0.8669]     0.8450      yes
mean wait, all callers           0.3540       [0.3475, 0.3605]     0.3521      yes

Little's law, L = lambda W, with lambda = 2.4 calls per minute
abandoned waits                               L     lambda W
recorded (the default)                   0.8497       0.8495
not recorded                             0.8497       0.7509
```

### How we know it is right

All three exact values lie inside their 99% intervals, and the proportion who hang up agrees to the
fourth decimal. The simulation has no knowledge of the birth-death argument: it only queues callers,
times them out and counts. So agreement with `ErlangA` is evidence that `seizeOrTimeout` withdraws a
request at the right moment, that the queue serves the others in the right order, and that a caller who
gives up is never also served.

### What to learn

**About 12% of callers hang up**, although the agents are busy only 85% of the time on average: the
callers answered, 2.4 × (1 − 0.1174) a minute, each take 4 minutes of one of 10 agents. Impatience
shortens the queue it is caused by.

**Decide what the time in queue means.** By default a wait that ends in a timeout is recorded in the
queue's time in queue, so the queue's statistics obey Little's law: 0.8497 against 0.8495. With
`recordRenegedWait = false`, the time in queue is the wait of the callers who were answered, which is
shorter, and L = λW no longer holds (0.8497 against 0.7509), because L still counts every caller who
waited. Neither is wrong; they answer different questions. Choose one, and say which you reported.

**Count those who give up yourself,** from `timedOut`. The model decides what giving up means: here a
caller who hangs up, in case 2 a patient who leaves, in case 5 an assembler who expedites.

---

## 2. The walk-in clinic, two ways

### The problem

The textbook's walk-in health clinic (section 7.5.1, `WalkInHealthClinic`, run by `Ch7Example8`) lets
low-priority patients give up on waiting for a doctor. It does that with events:
each such patient schedules an event for the end of their patience, and the event finds their request
in the doctors' queue and removes it with `removeAndTerminate`. This case writes the same clinic with
`seizeOrTimeout`, and checks that nothing else changes.

### The system

Exactly the textbook's clinic: patients arrive about every six minutes; a triage nurse sees each; five
doctors see them in order of priority (high, medium, low, with different treatment times); low-priority
patients balk if the doctors' queue is too long, and give up after waiting a uniform 10 to 20 minutes.
The textbook's section 7.5.1 has its table of distributions; none is changed here.

| Quantity | Distribution or value |
|---|---|
| Patience of a low-priority patient | uniform, 10 to 20 minutes, stream 6 |
| Replications | 30 of 600 minutes, as `Ch7Example8` |

### The model

The textbook draws this clinic in section 7.5.1. One step differs: where the textbook's patient schedules
its renege and then seizes a doctor, this patient seizes a doctor with a time limit.

### The code

`WalkInHealthClinicViaTimedSeize.kt` is the textbook's `WalkInHealthClinic.kt` with one part of the
patient's process replaced. The textbook's version, from `WalkInHealthClinic.kt`:

```kotlin
use(triageNurseR, delayDuration = triageRV)
// only low priority renege
if (priority == 3) {
    schedule(this@Patient::renegeAction, renegeTimeRV, message = this@Patient)
}
val a = seize(doctorR)
delay(service)
release(a)
```

```kotlin
private fun renegeAction(event: KSLEvent<Patient>) {
    val request: ProcessModel.Entity.Request? = doctorQ.find { it.entity == event.message }
    if (request != null) {
        doctorQ.removeAndTerminate(request)
        // reneging statistics
        renegingProb.value = 1.0
        numReneged.increment()
    }
}
```

#### 1. The replacement

```kotlin
use(triageNurseR, delayDuration = triageRV)
// only low priority renege: a seize with a time limit, the patience sampled where the event was
val a = if (priority == 3) {
    val patience = renegeTimeRV.value
    val result = seizeOrTimeout(doctorR, timeout = patience, recordRenegedWait = false)
    if (result is WaitResult.TimedOut) {
        // reneging statistics
        renegingProb.value = 1.0
        numReneged.increment()
        return@process
    }
    result.value
} else {
    seize(doctorR)
}
delay(service)
release(a)
```

The textbook's two pieces, an event scheduled before the seize and an event action that searches the
queue, become one `if`. The patience is drawn at the same point in the process, from the same stream, so
each patient's patience is the same in both models.

`recordRenegedWait = false` is there for a reason. `removeAndTerminate` does not record the wait of the
request it removes, so to give the same queue statistics as the textbook's clinic, the timed seize must
not record it either. (Case 1 explains what that choice does to Little's law.)

The rest of the file, the clinic's resources, random variables, statistics and the rest of the patient's
process, is the textbook's, unchanged.

#### 2. The comparison

```kotlin
fun main() {
    fun run(build: (Model) -> Unit): Model {
        val m = Model("WalkInClinic", autoCSVReports = false)
        build(m)
        m.lengthOfReplication = 10.0 * 60.0
        m.numberOfReplications = 30
        m.simulate()
        return m
    }
    val byEvent = run { WalkInHealthClinic(it, "Walk-In Clinic") }
    val byTimedSeize = run { WalkInHealthClinicViaTimedSeize(it, "Walk-In Clinic") }

    fun statistics(m: Model) = m.responses.associate { it.name to it.acrossReplicationStatistic } +
            m.counters.associate { it.name to it.acrossReplicationStatistic }
    val event = statistics(byEvent)
    val timed = statistics(byTimedSeize)
    println(String.format("%-40s %14s %14s %8s", "response", "by an event", "by seizeOrTimeout", "same"))
    var allSame = event.keys == timed.keys
    for (name in event.keys.sorted()) {
        val a = event.getValue(name)
        val b = timed[name]
        val same = b != null && a.average == b.average && a.variance == b.variance && a.count == b.count
        allSame = allSame && same
        println(String.format("%-40s %14.4f %14.4f %8s", name, a.average, b?.average ?: Double.NaN, if (same) "yes" else "NO"))
    }
    println()
    println(if (allSame) "Every response and counter is identical." else "The two models DIFFER.")
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 2.")
}
```

Both clinics are built under the same name, so their statistics have the same names, and every response
and counter is compared: its count, its average and its variance across the 30 replications.

### What it shows

```text
response                                    by an event by seizeOrTimeout     same
DoctorQ:NumInQ                                   0.9304         0.9304      yes
DoctorQ:TimeInQ                                  4.7896         4.7896      yes
Doctors:InstantaneousUtil                        0.7794         0.7794      yes
Doctors:NumActiveUnits                           5.0000         5.0000      yes
Doctors:NumBusyUnits                             3.8971         3.8971      yes
Doctors:PTimeBusy                                0.9751         0.9751      yes
Doctors:PTimeIdle                                0.0249         0.0249      yes
Doctors:PTimeInactive                            0.0000         0.0000      yes
Doctors:ScheduledUtil                            0.7794         0.7794      yes
Doctors:SeizeCount                              94.7333        94.7333      yes
Doctors:WIP                                      4.8276         4.8276      yes
TriageNurse:InstantaneousUtil                    0.4209         0.4209      yes
TriageNurse:NumActiveUnits                       1.0000         1.0000      yes
TriageNurse:NumBusyUnits                         0.4209         0.4209      yes
TriageNurse:PTimeBusy                            0.4209         0.4209      yes
TriageNurse:PTimeIdle                            0.5791         0.5791      yes
TriageNurse:PTimeInactive                        0.0000         0.0000      yes
TriageNurse:Q:NumInQ                             0.1544         0.1544      yes
TriageNurse:Q:TimeInQ                            0.8892         0.8892      yes
TriageNurse:ScheduledUtil                        0.4209         0.4209      yes
TriageNurse:SeizeCount                         101.2333       101.2333      yes
TriageNurse:WIP                                  0.5753         0.5753      yes
Walk-In Clinic:NumBalked                         0.0000         0.0000      yes
Walk-In Clinic:NumReneged                        4.3667         4.3667      yes
Walk-In Clinic:NumServed                        90.6333        90.6333      yes
Walk-In Clinic:ProbBalking                       0.0000         0.0000      yes
Walk-In Clinic:ProbReneging                      0.2934         0.2934      yes
Walk-In Clinic:TimeInSystem                     33.1647        33.1647      yes
Walk-In Clinic:TimeInSystemHigh                 43.7704        43.7704      yes
Walk-In Clinic:TimeInSystemLow                  17.1881        17.1881      yes
Walk-In Clinic:TimeInSystemMedium               31.4701        31.4701      yes

Every response and counter is identical.
```

### How we know it is right

All 31 statistics are identical, to every digit, including their variances. The clinic reneges (4.37
patients a replication), so the comparison exercises the timed seize rather than passing because nobody
gave up. Identical results mean each patient gave up at the same moment, from the same place in the queue,
leaving the same patients behind them, in both models.

### What to learn

**A time limit on a seize is a property of the wait,** not an event the model manages. Nothing has to be
searched for, and there is no window between scheduling the renege and starting the seize in which the
two can disagree.

**When rewriting a model, make the rewrite provable.** Drawing the patience at the same point, from the
same stream, and matching the statistics option is what turns "it looks the same" into "it is the same".

---

## 3. First free of two

### The problem

A small clinic has a doctor and a nurse practitioner, and either can see a walk-in patient. A patient
wants whichever is free first, and leaves if neither becomes free in time. With one queue per provider,
the patient must wait in both at once, and leave the other the moment one of them is free.

### The system

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between patients | exponential, mean 12 minutes | `timeBetweenArrivals` |
| Providers | a doctor and a nurse practitioner, one of each | `Doctor`, `NursePractitioner` |
| Visit length, either provider | exponential, mean 20 minutes | `ServiceTime` |
| Patience | exponential, mean 30 minutes | `Patience` |
| Replications | 20 of 100,000 minutes, after a warm-up of 10,000 | |

Each provider's queue is first come, first served. A waiting patient is in both, so each provider, when
free, takes the patient who has waited longest. The two queues therefore act as one queue served by two
providers, and with equal visit lengths the clinic is the M/M/2+M queue: case 1's model with c = 2.

### The model

```mermaid
flowchart LR
    A(["patient arrives"]) --> W{"first of: the doctor,<br/>the nurse practitioner,<br/>or patience runs out"}
    W -- doctor --> D["visit with the doctor"] --> L(["leaves"])
    W -- nurse practitioner --> N["visit with the nurse practitioner"] --> L
    W -- patience --> U(["leaves unseen"])
```

*Figure 2 — one wait with three ways out.*

### The code

`FirstFreeOfTwo.kt` in full.

#### 1. The clinic

```kotlin
class TwoProviderClinic(
    parent: ModelElement,
    val arrivalRate: Double = 1.0 / 12.0,
    meanServiceTime: Double = 20.0,
    meanPatience: Double = 30.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val doctor = ResourceWithQ(this, "Doctor")
    private val nursePractitioner = ResourceWithQ(this, "NursePractitioner")
    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(meanServiceTime, 2), "ServiceTime")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")

    private val leftUnseen = Response(this, "ProbLeftUnseen")
    private val seenByDoctor = Response(this, "ProbSeenByDoctor")
    private val numLeftUnseen = Counter(this, "NumLeftUnseen")

    /** The proportion of patients who leave without being seen. */
    val probLeftUnseen get() = leftUnseen
    /** Of the patients seen, the proportion seen by the doctor. */
    val probSeenByDoctor get() = seenByDoctor
    /** The time-average number of patients waiting. Every waiting patient is in the doctor's queue. */
    val numWaiting get() = doctor.waitingQ.numInQ
```

Two separate resources, each with its own queue. `numWaiting` reads the doctor's queue only: every
waiting patient is in it, so its length is the number waiting.

#### 2. The patient

```kotlin
    private inner class Patient : Entity() {
        val visit: KSLProcess = process(isDefaultProcess = true) {
            val byDoctor = doctor.seizeTrigger()
            val byNursePractitioner = nursePractitioner.seizeTrigger()
            val result = waitForFirst(byDoctor, byNursePractitioner, timeout = patience.value)
            if (result.timedOut) {
                leftUnseen.value = 1.0
                numLeftUnseen.increment()
                return@process
            }
            leftUnseen.value = 0.0
            val provider = if (result.firedBy(byDoctor)) byDoctor else byNursePractitioner
            seenByDoctor.value = if (provider === byDoctor) 1.0 else 0.0
            val allocation = result.valueOf(provider)
            delay(serviceTime)
            release(allocation)
        }
    }

    private val generator = EntityGenerator(::Patient, timeBetweenArrivals, timeBetweenArrivals)
}
```

`seizeTrigger()` makes a **trigger**: a description of a seize that `waitForFirst` can wait on alongside
other things. It does not seize anything by itself. `waitForFirst(byDoctor, byNursePractitioner, timeout
= …)` places the patient's request in both queues and waits until the first of three things happens. When
one provider is given to the patient, the request in the other queue is withdrawn at once, before anyone
else can be served by it, and nothing is allocated there.

The result says which trigger ended the wait: `firedBy(byDoctor)` asks, and `valueOf(provider)` gives the
allocation of the provider who did. `valueOf` of a trigger that did not end the wait throws; that is why
the code asks first.

#### 3. The study

```kotlin
fun main() {
    val exact = ErlangA(lambda = 1.0 / 12.0, mu = 1.0 / 20.0, theta = 1.0 / 30.0, c = 2)

    val model = Model("FirstFreeOfTwo", autoCSVReports = false)
    val clinic = TwoProviderClinic(model, name = "Clinic")
    model.numberOfReplications = 20
    model.lengthOfReplication = 100_000.0
    model.lengthOfReplicationWarmUp = 10_000.0
    model.simulate()

    println("The two-provider clinic against the M/M/2+M answers, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    val left = clinic.probLeftUnseen.acrossReplicationStatistic
    val waiting = clinic.numWaiting.acrossReplicationStatistic
    for ((label, stat, value) in listOf(
        Triple("probability of leaving", left, exact.probAbandon),
        Triple("mean number waiting", waiting, exact.meanNumberWaiting)
    )) {
        val ci = stat.confidenceInterval(0.99)
        println(
            String.format(
                "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
                String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), value, if (value in ci) "yes" else "NO"
            )
        )
    }
    println()
    println(String.format("Of the patients seen, %.4f were seen by the doctor.", clinic.probSeenByDoctor.acrossReplicationStatistic.average))
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 3.")
}
```

The exact values come from case 1's `ErlangA`, with two servers.

### What it shows

```text
The two-provider clinic against the M/M/2+M answers, 99% confidence intervals
quantity                       estimate               interval      exact   inside
probability of leaving           0.1909       [0.1864, 0.1954]     0.1907      yes
mean number waiting              0.4756       [0.4628, 0.4884]     0.4768      yes

Of the patients seen, 0.5406 were seen by the doctor.
```

### How we know it is right

Both exact values lie inside their intervals. The M/M/2+M answer assumes one queue and two servers; the
model has two queues and a patient in both. Agreement means the withdrawal from the other queue is
complete and immediate: a patient is never seen twice, never left behind in the other queue to block it,
and never served out of turn.

### What to learn

**The order of the triggers settles a tie.** 54% of the patients seen saw the doctor, not 50%. When a
patient arrives to find *both* providers free, both triggers could fire at once, and the first one in the
argument list wins: the doctor. When only one is free, or when they become free at different moments, the
order plays no part. If a tie should be settled some other way, by a preference or at random, order the
triggers accordingly for each patient.

**One wait, any mix of things.** The same `waitForFirst` accepts seize triggers for resources and pools,
signal triggers, items triggers on blocking queues and message triggers on mailboxes, in any combination,
except that one wait may include at most one signal trigger (an entity waits in only one signal's queue); a
second throws. Case 5 uses three different kinds.

---

## 4. The shuttle that leaves full or on time

### The problem

A shuttle runs from a stop to a terminal and back. A shuttle that waits until it is full gives the riders
at the stop a long wait when riders are few; one that leaves as soon as a rider boards wastes seats. This
shuttle leaves when it is full, or a fixed time after it began waiting with a rider aboard, whichever comes
first. Meanwhile riders do not wait for ever: one who has waited too long takes a taxi. Both parties wait
with a deadline, and each one's wait can be ended by the other.

### The system

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between riders | exponential, mean 2 minutes (0.5 a minute) | `timeBetweenArrivals` |
| Seats on the shuttle | 12 | `capacity` |
| Longest hold with a rider aboard | 10 minutes | `maxHold` |
| Round trip | triangular, 10, 15, 20 minutes | `RoundTrip` |
| Rider's patience | exponential, mean 30 minutes | `Patience` |
| Replications | 20 of 10,000 minutes, after a warm-up of 1,000 | |

This system has no simple exact answer. It is checked instead against the same system written with
events.

### The model

```mermaid
flowchart LR
    subgraph Rider
        R1(["arrives"]) --> R2{"boarded before<br/>patience runs out?"}
        R2 -- yes --> R3(["rides"])
        R2 -- no --> R4(["takes a taxi"])
    end
    subgraph Shuttle
        S1{"any rider<br/>waiting?"} -- no --> S2["wait for a rider"] --> S3
        S1 -- yes --> S3{"full, or<br/>10 minutes?"}
        S3 --> S4["board up to 12"] --> S5["round trip"] --> S1
    end
```

*Figure 3 — two processes, each waiting with a deadline, each able to end the other's wait.*

### The code

`ShuttleStop.kt` in full.

#### 1. The stop

```kotlin
class ShuttleStop(
    parent: ModelElement,
    val capacity: Int = 12,
    val maxHold: Double = 10.0,
    arrivalRate: Double = 0.5,
    meanPatience: Double = 30.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val roundTrip = RandomVariable(this, TriangularRV(10.0, 15.0, 20.0, 3), "RoundTrip")

    private val stopQ = Queue<Rider>(this, "StopQ")
    private val riderArrived = Signal(this, "RiderArrived")
    private val shuttleFull = Signal(this, "ShuttleFull")

    private val tookTaxi = Response(this, "ProbTaxi")
    private val tripLoad = Response(this, "TripLoad")
    private val numFullDepartures = Counter(this, "NumFullDepartures")
    private val numTimedDepartures = Counter(this, "NumTimedDepartures")
```

The riders waiting at the stop stand in a queue of the model's own, `StopQ`, in arrival order; the queue
records the number waiting over time and, for each rider removed with its statistics, the time it waited.
Two signals connect the riders to the shuttle: `RiderArrived` and `ShuttleFull`. The other statistics are
the proportion who take a taxi and each trip's load.

#### 2. The rider

```kotlin
private inner class Rider : Entity() {
    val boarded = Suspension("boarded")

    val trip: KSLProcess = process(isDefaultProcess = true) {
        val myPatience = patience.value
        stopQ.enqueue(this@Rider)
        riderArrived.signal()
        if (stopQ.size >= capacity) shuttleFull.signal()
        val result = suspendForOrTimeout(boarded, timeout = myPatience)
        if (result.timedOut) {
            // a rider who gives up is not a wait for the shuttle
            stopQ.remove(this@Rider, waitStats = false)
            tookTaxi.value = 1.0
        }
    }
}
```

Each rider owns a **suspension**, `boarded`: a named point at which its process can be suspended and later
resumed by another process. The rider joins the queue, tells the shuttle it has arrived, and, if it has
filled the shuttle, says so. Then it waits with `suspendForOrTimeout(boarded, timeout = myPatience)`: it is
suspended until the shuttle resumes it, or until its patience runs out, whichever comes first. If the time
runs out it leaves the queue with `waitStats = false`, so that the queue's time-in-queue counts only riders
who board, and takes a taxi. If it is resumed there is nothing more to do: the shuttle has already taken it
from the queue, which recorded its wait.

The suspension is the rider's own, not a line: nothing in it says who came first. That is why the stop keeps
the riders in a queue of its own, for the shuttle to board in order and for the statistics.

A signal reaches only the processes waiting on it when it is sent. `riderArrived.signal()` while the
shuttle is away reaches no one, and that is correct: the shuttle looks at the queue when it returns.

#### 3. The shuttle

```kotlin
    private inner class Shuttle : Entity() {
        val service: KSLProcess = process {
            while (true) {
                if (stopQ.isEmpty) waitFor(riderArrived)
                if (stopQ.size < capacity) waitForOrTimeout(shuttleFull, timeout = maxHold)
                val boarding = stopQ.toList().take(capacity)
                if (boarding.isEmpty()) continue        // every rider gave up while the shuttle waited
                for (rider in boarding) {
                    stopQ.remove(rider)
                    tookTaxi.value = 0.0
                    resume(rider.boarded)
                }
                tripLoad.value = boarding.size.toDouble()
                if (boarding.size == capacity) numFullDepartures.increment() else numTimedDepartures.increment()
                delay(roundTrip)
            }
        }
    }

    private val generator = EntityGenerator(::Rider, timeBetweenArrivals, timeBetweenArrivals)

    override fun initialize() {
        activate(Shuttle().service)
    }
}
```

The shuttle is a process that loops. With nobody waiting, it waits for a rider, with no time limit. Then,
unless the shuttle is already full, it waits for `ShuttleFull` with a limit of `maxHold`: the second
deadline of the case. Whichever way that wait ends, it boards up to twelve riders in arrival order: it
takes each from the queue, which records its wait, and calls `resume(rider.boarded)`, which ends that
rider's `suspendForOrTimeout`. If every rider gave up during the hold, there is nobody to board, and the
shuttle starts again.

`resume` is the way one process ends another's suspension. Calling `resumeProcess()` on the rider instead
would throw: an entity in a wait with a time limit, or in any wait for the first of several things, can be
resumed only through what it waits on.

#### 4. The same stop, with every rider waiting on one signal

```kotlin
class ShuttleStopBySignal(
    parent: ModelElement,
    val capacity: Int = 12,
    val maxHold: Double = 10.0,
    arrivalRate: Double = 0.5,
    meanPatience: Double = 30.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val roundTrip = RandomVariable(this, TriangularRV(10.0, 15.0, 20.0, 3), "RoundTrip")

    private val boarding = Signal(this, "Boarding")
    private val riderArrived = Signal(this, "RiderArrived")
    private val shuttleFull = Signal(this, "ShuttleFull")

    private val waitForShuttle = Response(this, "WaitForShuttle")
    private val tookTaxi = Response(this, "ProbTaxi")
    private val tripLoad = Response(this, "TripLoad")
    private val numFullDepartures = Counter(this, "NumFullDepartures")
    private val numTimedDepartures = Counter(this, "NumTimedDepartures")

    private inner class Rider : Entity() {
        val trip: KSLProcess = process(isDefaultProcess = true) {
            val myPatience = patience.value
            riderArrived.signal()
            // this rider is about to join the line
            if (boarding.waitingQ.size + 1 >= capacity) shuttleFull.signal()
            val result = waitForOrTimeout(boarding, timeout = myPatience, waitStats = false)
            if (result.timedOut) tookTaxi.value = 1.0
        }
    }

    private inner class Shuttle : Entity() {
        val service: KSLProcess = process {
            while (true) {
                if (boarding.waitingQ.isEmpty) waitFor(riderArrived)
                if (boarding.waitingQ.size < capacity) waitForOrTimeout(shuttleFull, timeout = maxHold)
                val aboard = boarding.holdingQueueAsList().take(capacity)
                if (aboard.isEmpty()) continue        // every rider gave up while the shuttle waited
                for (rider in aboard) {
                    waitForShuttle.value = time - rider.createTime
                    tookTaxi.value = 0.0
                }
                boarding.signal(0 until aboard.size)
                tripLoad.value = aboard.size.toDouble()
                if (aboard.size == capacity) numFullDepartures.increment() else numTimedDepartures.increment()
                delay(roundTrip)
            }
        }
    }

    private val generator = EntityGenerator(::Rider, timeBetweenArrivals, timeBetweenArrivals)

    override fun initialize() {
        activate(Shuttle().service)
    }
}
```

Here no rider has a suspension of its own, and the stop keeps no line. Every rider waits on the one signal
`Boarding`, with `waitForOrTimeout(boarding, timeout = myPatience, waitStats = false)`. A signal holds the
processes waiting on it in a queue of its own, in arrival order, so that queue is the line: its number in
queue is the number waiting, and a rider whose patience runs out leaves it by itself. The shuttle reads who
is waiting (`holdingQueueAsList`), records each boarding rider's wait, and boards the first of them with
`boarding.signal(0 until aboard.size)`, which resumes the riders at those ranks. Because the riders leave
the signal's queue without their waits recorded (`waitStats = false`, so that a rider who gives up does not
count), the shuttle records the waits of those who board, as the event version does.

A rider tells the shuttle it has filled it before it joins the signal's queue, so it counts itself:
`boarding.waitingQ.size + 1`.

#### 5. The same stop, written with events

```kotlin
class ShuttleStopByEvents(
    parent: ModelElement,
    val capacity: Int = 12,
    val maxHold: Double = 10.0,
    arrivalRate: Double = 0.5,
    meanPatience: Double = 30.0,
    name: String? = null
) : ModelElement(parent, name) {

    private val timeBetweenArrivals = RandomVariable(this, ExponentialRV(1.0 / arrivalRate, 1), "TimeBetweenArrivals")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val roundTrip = RandomVariable(this, TriangularRV(10.0, 15.0, 20.0, 3), "RoundTrip")

    private inner class Rider(val arrivalTime: Double) {
        var gaveUp: KSLEvent<Rider>? = null
    }

    private val waiting = ArrayDeque<Rider>()
    private var shuttleAtStop = true
    private var holdEnds: KSLEvent<Nothing>? = null

    private val numWaiting = TWResponse(this, "NumWaiting")
    private val waitForShuttle = Response(this, "WaitForShuttle")
    private val tookTaxi = Response(this, "ProbTaxi")
    private val tripLoad = Response(this, "TripLoad")
    private val numFullDepartures = Counter(this, "NumFullDepartures")
    private val numTimedDepartures = Counter(this, "NumTimedDepartures")

    override fun initialize() {
        waiting.clear()
        shuttleAtStop = true
        holdEnds = null
        schedule(::arrival, timeBetweenArrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val rider = Rider(time)
        rider.gaveUp = schedule(::riderGivesUp, patience, message = rider)
        waiting.addLast(rider)
        numWaiting.increment()
        if (shuttleAtStop) {
            if (waiting.size >= capacity) depart()
            else if (holdEnds == null) holdEnds = schedule(::holdTimeUp, maxHold)
        }
        schedule(::arrival, timeBetweenArrivals)
    }

    private fun riderGivesUp(event: KSLEvent<Rider>) {
        waiting.remove(event.message!!)
        numWaiting.decrement()
        tookTaxi.value = 1.0
    }

    private fun holdTimeUp(event: KSLEvent<Nothing>) {
        holdEnds = null
        if (waiting.isNotEmpty()) depart()
    }

    private fun depart() {
        holdEnds?.let { if (it.isScheduled) it.cancel = true }
        holdEnds = null
        val boarding = waiting.take(capacity)
        for (rider in boarding) {
            waiting.removeFirst()
            // an event due after the end of the replication was never put on the calendar
            rider.gaveUp?.let { if (it.isScheduled) it.cancel = true }
            numWaiting.decrement()
            waitForShuttle.value = time - rider.arrivalTime
            tookTaxi.value = 0.0
        }
        tripLoad.value = boarding.size.toDouble()
        if (boarding.size == capacity) numFullDepartures.increment() else numTimedDepartures.increment()
        shuttleAtStop = false
        schedule(::shuttleReturns, roundTrip)
    }

    private fun shuttleReturns(event: KSLEvent<Nothing>) {
        shuttleAtStop = true
        if (waiting.size >= capacity) depart()
        else if (waiting.isNotEmpty()) holdEnds = schedule(::holdTimeUp, maxHold)
    }
}
```

The same rules, written as event routines. A rider is a small record of its arrival time and the event at
which it will give up. An arrival schedules that event, joins the list and, if the shuttle is at the stop,
either sends it or starts its hold. A departure cancels the hold, boards riders by cancelling their give-up
events, and schedules the return.

The event version must undo by hand what the process version gets for free. When a rider boards, its
give-up event must be cancelled; in the process version, `resume` ends the rider's wait and its timer with
it. And an event due after the end of the replication is never put on the calendar, so cancelling it would
throw; hence the `isScheduled` checks.

All three versions draw the same random numbers in the same order: interarrival times from stream 1 as riders
arrive, patience from stream 2 as each rider arrives, round trips from stream 3 as the shuttle leaves.

#### 6. The comparison

```kotlin
fun main() {
    fun run(build: (Model) -> Unit): Model {
        val m = Model("ShuttleStop", autoCSVReports = false)
        build(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 10_000.0
        m.lengthOfReplicationWarmUp = 1_000.0
        m.simulate()
        return m
    }
    val byQueue = run { ShuttleStop(it, name = "Stop") }
    val bySignal = run { ShuttleStopBySignal(it, name = "Stop") }
    val byEvents = run { ShuttleStopByEvents(it, name = "Stop") }

    fun statistics(m: Model) = m.responses.associate { it.name to it.acrossReplicationStatistic } +
            m.counters.associate { it.name to it.acrossReplicationStatistic }
    val q = statistics(byQueue)
    val s = statistics(bySignal)
    val e = statistics(byEvents)
    // each statistic of the events version, and where the other two keep it
    val shared = listOf(
        Triple("NumWaiting", "StopQ:NumInQ", "Boarding:HoldQ:NumInQ"),
        Triple("WaitForShuttle", "StopQ:TimeInQ", "WaitForShuttle"),
        Triple("ProbTaxi", "ProbTaxi", "ProbTaxi"),
        Triple("TripLoad", "TripLoad", "TripLoad"),
        Triple("NumFullDepartures", "NumFullDepartures", "NumFullDepartures"),
        Triple("NumTimedDepartures", "NumTimedDepartures", "NumTimedDepartures")
    )
    println(String.format("%-20s %12s %12s %12s %12s %6s", "statistic", "by queue", "by signal", "by events", "half-width", "same"))
    var allSame = true
    for ((name, inQueue, inSignal) in shared) {
        val a = q.getValue(inQueue)
        val b = s.getValue(inSignal)
        val c = e.getValue(name)
        val same = listOf(a, b).all { it.average == c.average && it.variance == c.variance && it.count == c.count }
        allSame = allSame && same
        println(String.format("%-20s %12.4f %12.4f %12.4f %12.4f %6s", name, a.average, b.average, c.average, c.halfWidth, if (same) "yes" else "NO"))
    }
    println()
    println(if (allSame) "The three models agree exactly." else "The three models DIFFER.")
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 4.")
}
```

### What it shows

```text
statistic                by queue    by signal    by events   half-width   same
NumWaiting                 4.5340       4.5340       4.5340       0.0292    yes
WaitForShuttle             9.9548       9.9548       9.9548       0.0666    yes
ProbTaxi                   0.3008       0.3008       0.3008       0.0045    yes
TripLoad                   8.4065       8.4065       8.4065       0.0792    yes
NumFullDepartures         78.1000      78.1000      78.1000       4.8230    yes
NumTimedDepartures       296.0000     296.0000     296.0000       3.9567    yes

The three models agree exactly.
```

The queue version keeps the number waiting and the wait in `StopQ` (`StopQ:NumInQ`, `StopQ:TimeInQ`), and
the signal version keeps the number waiting in the signal's queue (`Boarding:HoldQ:NumInQ`); the
comparison matches each to its counterpart in the event version.

### How we know it is right

The three formulations agree in every statistic, exactly. Both ways of leaving occur, 78 full departures
and 296 timed ones a replication, and three in ten riders give up, so every deadline in the model is
exercised many thousands of times. Each formulation would have to make the same mistake at the same moment
for the agreement to hide one.

### What to learn

**The stop loses three riders in ten.** Most trips leave on time, not full, with eight riders on average:
the hold rarely fills the shuttle, and a rider who arrives as it leaves faces a round trip of 15 minutes on
average before it is back. Varying `maxHold` and `capacity` (they are constructor parameters, and
controls of an experiment) asks whether holding longer would keep more riders than it costs them in
waiting.

**Two deadlines, no bookkeeping.** In the process versions each party simply waits, with a limit, for the
other. The event version needs the give-up events cancelled, the hold event remembered and cancelled, and
guards for events past the end of the run. That bookkeeping is where event models of impatience go wrong.

**Who keeps the line.** When each waiter has its own suspension, as the queue version's riders do, nothing
orders them, and the model keeps the line: a queue of its own, which also keeps the statistics. When every
waiter waits for the same thing, a signal's queue is the line, and a waiter who gives up leaves it by
itself. A signal suits waiters that are all served the same way, in order; a suspension of each waiter's
own suits waiters that are picked out one by one, or resumed by different parties.

---

## 5. Kitting, with expediting

### The problem

An assembler builds products from kits. A kitting station puts kits into a bin, and the assembler takes
them one at a time. While waiting for a kit, three different things can end the wait, and the assembler
must respond to whichever comes first: a kit arrives; the supervisor calls the assembler away to a rework
job; or the wait has gone on so long that the assembler orders an expedited kit.

### The system

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between kits | exponential, mean 10 minutes | `TimeBetweenKits` |
| Assembly time | uniform, 6 to 9 minutes | `AssemblyTime` |
| Time between the supervisor's recalls | exponential, mean 60 minutes | `TimeBetweenRecalls` |
| Rework job | 10 minutes | `ReworkTime` |
| Wait before expediting | 15 minutes | `expediteAfter` |
| Expedited kit's lead time | 5 minutes; one at a time | `expediteLeadTime` |
| Replications | 20 of 10,000 minutes | |

The supervisor calls for rework whether or not the assembler is waiting. A call made while the assembler is
busy is not remembered: it reaches no one.

### The model

```mermaid
flowchart LR
    K["kitting station"] -- kits --> B[("kit bin")]
    E["expedited kit"] -- after 5 minutes --> B
    S["supervisor"] -- recall --> W
    B --> W{"first of: a kit,<br/>a recall,<br/>or 15 minutes"}
    W -- kit --> A1["assemble"] --> W
    W -- recall --> A2["rework"] --> W
    W -- 15 minutes --> A3["order an expedited kit"] --> W
```

*Figure 4 — the assembler's wait, with three ways out.*

### The code

`KittingWithExpediting.kt` in full.

#### 1. The cell

```kotlin
class KittingCell(
    parent: ModelElement,
    timeBetweenKits: RVariableIfc = ExponentialRV(10.0, 1),
    assemblyTime: RVariableIfc = UniformRV(6.0, 9.0, 2),
    timeBetweenRecalls: RVariableIfc = ExponentialRV(60.0, 3),
    reworkTime: RVariableIfc = ConstantRV(10.0),
    val expediteAfter: Double = 15.0,
    val expediteLeadTime: Double = 5.0,
    private val trace: Boolean = false,
    name: String? = null
) : ProcessModel(parent, name) {

    private val kitInterval = RandomVariable(this, timeBetweenKits, "TimeBetweenKits")
    private val assembly = RandomVariable(this, assemblyTime, "AssemblyTime")
    private val recallInterval = RandomVariable(this, timeBetweenRecalls, "TimeBetweenRecalls")
    private val rework = RandomVariable(this, reworkTime, "ReworkTime")

    private inner class Kit(val expedited: Boolean) : QObject()

    private val kitBin: BlockingQueue<Kit> = BlockingQueue(this, name = "KitBin")
    private val recall = Signal(this, "Recall")

    private val kitsMade = Counter(this, "KitsMade")
    private val kitsExpedited = Counter(this, "KitsExpedited")
    private val kitsAssembled = Counter(this, "KitsAssembled")
    private val recallsSent = Counter(this, "RecallsSent")
    private val recallsAnswered = Counter(this, "RecallsAnswered")
    private val unaccounted = Response(this, "KitsUnaccounted")

    private var kitInAssembly = 0
    private var expediteOutstanding = false

    private fun log(what: String) {
        if (trace) println(String.format("%8.2f  %s", time, what))
    }
```

The random times are constructor parameters of type `RVariableIfc`, so `main` can run the cell with
constant times for a trace that can be followed by hand. The bin is a `BlockingQueue` of kits; the recall
is a `Signal`. `log` prints a line only when the cell is built with `trace = true`.

#### 2. The kitting station, the supervisor and the expedited kit

```kotlin
private inner class KittingStation : Entity() {
    val supply: KSLProcess = process {
        while (true) {
            delay(kitInterval)
            kitsMade.increment()
            log("a kit goes into the bin")
            send(Kit(expedited = false), kitBin)
        }
    }
}

private inner class Supervisor : Entity() {
    val oversight: KSLProcess = process {
        while (true) {
            delay(recallInterval)
            recallsSent.increment()
            log("the supervisor calls for rework")
            recall.signal()
        }
    }
}

private inner class Expediter : Entity() {
    val delivery: KSLProcess = process {
        delay(expediteLeadTime)
        kitsExpedited.increment()
        expediteOutstanding = false
        log("the expedited kit goes into the bin")
        send(Kit(expedited = true), kitBin)
    }
}
```

Three small processes. The station sends a kit into the bin at each interval. The supervisor signals a
recall at each interval, whether or not anyone is waiting for it. An `Expediter` is created for each
expedited kit: it waits out the lead time and sends the kit into the bin.

#### 3. The assembler

```kotlin
private inner class Assembler : Entity() {
    val work: KSLProcess = process {
        while (true) {
            val kitArrives = kitBin.itemsTrigger(amount = 1)
            val calledAway = recall.trigger()
            val result = waitForFirst(kitArrives, calledAway, timeout = expediteAfter)
            when (result.trigger) {
                kitArrives -> {
                    val kit = result.valueOf(kitArrives).first()
                    log("the assembler takes a${if (kit.expedited) "n expedited" else ""} kit, after waiting ${"%.2f".format(result.timeWaited)}")
                    kitInAssembly = 1
                    delay(assembly)
                    kitInAssembly = 0
                    kitsAssembled.increment()
                }
                calledAway -> {
                    recallsAnswered.increment()
                    log("the assembler answers the recall")
                    delay(rework)
                }
                null -> {
                    if (!expediteOutstanding) {
                        expediteOutstanding = true
                        log("the assembler has waited ${"%.2f".format(result.timeWaited)} and expedites a kit")
                        activate(Expediter().delivery)
                    }
                }
            }
        }
    }
}
```

The wait is one call with three ways out. `kitBin.itemsTrigger(amount = 1)` is a trigger for one item from
the bin; its value, when it fires, is the list of items taken. `recall.trigger()` is a trigger for the
signal. The timeout is the third way out. `result.trigger` is the trigger that ended the wait, or `null` if
the time ran out, so a `when` on it handles each case. An assembler who has already expedited a kit that
has not yet arrived does not expedite another; it simply waits again.

New triggers are made for each wait. A trigger holds no state of its own, so making them where the wait is
made costs nothing and keeps each wait's ways out together.

#### 4. Starting each replication, and the accounting

```kotlin
    override fun initialize() {
        kitInAssembly = 0
        expediteOutstanding = false
        activate(KittingStation().supply)
        activate(Supervisor().oversight)
        activate(Assembler().work)
    }

    /** Every kit made or expedited is assembled, being assembled, or in the bin. */
    override fun replicationEnded() {
        unaccounted.value = kitsMade.value + kitsExpedited.value - kitsAssembled.value - kitInAssembly - kitBin.channelQ.size
    }
}
```

At the end of each replication, every kit made or expedited must be assembled, being assembled, or still in
the bin. `KitsUnaccounted` records the difference, which must be zero.

#### 5. The study

```kotlin
fun main() {
    println("A short run with constant times: kits every 20 minutes, assembly 8, recalls every 30, rework 10")
    val short = Model("KittingTrace", autoCSVReports = false)
    KittingCell(
        short, timeBetweenKits = ConstantRV(20.0), assemblyTime = ConstantRV(8.0),
        timeBetweenRecalls = ConstantRV(30.0), reworkTime = ConstantRV(10.0), trace = true, name = "Cell"
    )
    short.numberOfReplications = 1
    short.lengthOfReplication = 100.0
    short.simulate()

    println()
    println("A long run with random times")
    val long = Model("Kitting", autoCSVReports = false)
    KittingCell(long, name = "Cell")
    long.numberOfReplications = 20
    long.lengthOfReplication = 10_000.0
    long.simulate()
    val statistics = long.responses.associate { it.name to it.acrossReplicationStatistic } +
            long.counters.associate { it.name to it.acrossReplicationStatistic }
    fun stat(name: String) = statistics.getValue(name)
    println(String.format("%-18s %10s %12s", "per replication", "average", "half-width"))
    for (name in listOf("KitsMade", "KitsExpedited", "KitsAssembled", "RecallsSent", "RecallsAnswered", "KitsUnaccounted")) {
        val s = stat(name)
        println(String.format("%-18s %10.2f %12.2f", name, s.average, s.halfWidth))
    }
    val unaccounted = stat("KitsUnaccounted")
    println()
    println(
        if (unaccounted.min == 0.0 && unaccounted.max == 0.0) "Every kit is accounted for in every replication."
        else "Kits went MISSING."
    )
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 5.")
}
```

### What it shows

```text
A short run with constant times: kits every 20 minutes, assembly 8, recalls every 30, rework 10
   15.00  the assembler has waited 15.00 and expedites a kit
   20.00  a kit goes into the bin
   20.00  the assembler takes a kit, after waiting 5.00
   20.00  the expedited kit goes into the bin
   28.00  the assembler takes an expedited kit, after waiting 0.00
   30.00  the supervisor calls for rework
   40.00  a kit goes into the bin
   40.00  the assembler takes a kit, after waiting 4.00
   60.00  the supervisor calls for rework
   60.00  the assembler answers the recall
   60.00  a kit goes into the bin
   70.00  the assembler takes a kit, after waiting 0.00
   80.00  a kit goes into the bin
   80.00  the assembler takes a kit, after waiting 2.00
   90.00  the supervisor calls for rework
   90.00  the assembler answers the recall
  100.00  a kit goes into the bin
  100.00  the assembler takes a kit, after waiting 0.00

A long run with random times
per replication       average   half-width
KitsMade               999.70        11.64
KitsExpedited           41.65         2.88
KitsAssembled         1038.75        10.00
RecallsSent            166.55         5.46
RecallsAnswered         31.50         2.86
KitsUnaccounted          0.00         0.00

Every kit is accounted for in every replication.
```

### How we know it is right

**The trace can be checked line by line.**

- At 15 the assembler has waited 15 minutes with no kit, so it expedites one, due at 20, and waits again.
- At 20 the station's kit and the expedited kit both go into the bin. The assembler takes the first, after
  waiting 5 minutes since 15, and assembles until 28, when it takes the expedited kit at once.
- The recall at 30 reaches no one: the assembler is assembling.
- At 60 a recall and a kit come at the same instant, and the recall wins.
- At 100 a kit arrives at the instant the assembler finishes its rework, and the assembler takes the kit.

Why does each tie go the way it does? Events at the same instant run in order of priority, and at equal
priority in the order they were scheduled. The supervisor's delay ending at 60 was scheduled at 30, the
station's at 40, so the recall comes first and finds the assembler waiting. At 100 the station's delay was
scheduled at 80 and the end of the rework at 90, so the kit is already in the bin when the assembler looks.

**The long run accounts for every kit,** in every one of the 20 replications, while all three ways of
ending the wait occur many times over.

### What to learn

**A signal is not a mailbox.** The supervisor sends 167 recalls a replication and the assembler answers
32. The rest were sent while the assembler was busy, and a `Signal` wakes only those waiting on it at that
moment. If a recall should wait to be answered, it must be kept somewhere that persists: a blocking queue,
or a mailbox.

**Ties are settled by the calendar,** not by the order of the triggers in the call. The order of the
triggers matters only when more than one could fire *at the moment the wait begins* (case 3). When the
causes arrive later, the one whose event runs first wins.

---

## 6. Beds from a pool, with a fallback

### The problem

A hospital ward has twelve beds in three rooms of four. Patients arrive for it around the clock, and a patient
who has waited too long for a bed is sent to an overflow unit elsewhere. The hospital also has a step-down unit
with four beds in two rooms: would letting a waiting patient take a step-down bed, if one frees first, keep
patients out of the overflow unit? Each unit's beds are a **pool**: a patient needs a bed, not a particular
room.

### The system

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between patients | exponential, mean 1/1.4 hours (1.4 an hour) | `timeBetweenArrivals` |
| Length of stay | exponential, mean 8 hours, in either unit | `Stay` |
| Patience for a bed | exponential, mean 4 hours | `Patience` |
| Ward beds | a pool of 3 rooms of 4 beds | `Beds` |
| Step-down beds | a pool of 2 rooms of 2 beds | `StepDownBeds` |
| Replications | 20 of 20,000 hours, after a warm-up of 2,000 | |

With exponential times both systems have exact answers: any bed serves any patient, so a pool acts as one
resource of all its beds, and the ward alone is an M/M/12+M queue, the queue of case 1 with twelve servers.

### The model

```mermaid
flowchart LR
    A(["patient arrives"]) --> W{"a bed before<br/>patience runs out?"}
    W -- "ward bed" --> S1["stays"] --> D(["leaves"])
    W -- "step-down bed<br/>(part 2)" --> S2["stays"] --> D
    W -- no --> O(["overflow unit"])
```

*Figure 5 — one wait, for a bed from either pool, with a time limit.*

### The code

`HospitalBeds.kt` in full.

#### 1. The ward

```kotlin
class Ward(
    parent: ModelElement,
    numRooms: Int = 3,
    bedsPerRoom: Int = 4,
    val arrivalRate: Double = 1.4,
    meanStay: Double = 8.0,
    meanPatience: Double = 4.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val rooms = (1..numRooms).map { Resource(this, "Room$it", capacity = bedsPerRoom) }
    private val beds = ResourcePoolWithQ(this, rooms, name = "Beds")

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val stay = RandomVariable(this, ExponentialRV(meanStay, 2), "Stay")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")

    private val overflowed = Response(this, "ProbOverflow")

    /** The proportion of patients sent to the overflow unit. */
    val probOverflow get() = overflowed
    /** The time-average number of patients waiting for a bed. */
    val numWaiting get() = beds.waitingQ.numInQ
    /** The time patients wait, those who give up included. */
    val timeWaiting get() = beds.waitingQ.timeInQ
```

The ward's beds are three `Resource`s of capacity 4, one per room, gathered into a `ResourcePoolWithQ`. A
patient asks the pool for one bed, and the pool's selection rule finds a room with one free: the patient never
names a room. The pool keeps one queue for the patients waiting, `Beds:Q`, whose number in queue and time in
queue are the statistics.

#### 2. The patient

```kotlin
private inner class Patient : Entity() {
    val admission: KSLProcess = process(isDefaultProcess = true) {
        val result = seizeOrTimeout(beds, timeout = patience.value)
        if (result.timedOut) {
            overflowed.value = 1.0
            return@process
        }
        overflowed.value = 0.0
        delay(stay)
        release(result.value)
    }
}

private val generator = EntityGenerator(::Patient, timeBetweenArrivals, timeBetweenArrivals)
```

`seizeOrTimeout` takes a pool as it takes a resource: the patient waits in the pool's queue for one bed, for at
most its patience. If the time runs out the patient leaves the queue, its wait recorded, and is sent to the
overflow unit; otherwise it holds the bed from whichever room the pool chose for its stay, and releases the
pool allocation, which frees that bed.

#### 3. The ward with a step-down unit

```kotlin
class WardWithStepDown(
    parent: ModelElement,
    numRooms: Int = 3,
    bedsPerRoom: Int = 4,
    stepDownRooms: Int = 2,
    bedsPerStepDownRoom: Int = 2,
    val arrivalRate: Double = 1.4,
    meanStay: Double = 8.0,
    meanPatience: Double = 4.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val wardBeds = ResourcePoolWithQ(this, (1..numRooms).map { Resource(this, "Room$it", capacity = bedsPerRoom) }, name = "Beds")
    private val stepDownBeds = ResourcePoolWithQ(
        this, (1..stepDownRooms).map { Resource(this, "StepDownRoom$it", capacity = bedsPerStepDownRoom) }, name = "StepDownBeds"
    )

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val stay = RandomVariable(this, ExponentialRV(meanStay, 2), "Stay")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")

    private val overflowed = Response(this, "ProbOverflow")
    private val placedInStepDown = Response(this, "ProbStepDown")
    private val waitForBed = Response(this, "WaitForBed")

    /** The proportion of patients sent to the overflow unit. */
    val probOverflow get() = overflowed
    /** The proportion of placed patients given a step-down bed. */
    val probStepDown get() = placedInStepDown
    /** The time-average number of patients waiting: each waits in both queues, so the ward's counts them all. */
    val numWaiting get() = wardBeds.waitingQ.numInQ
    /**
     *  The time patients wait, those who give up included. A patient who takes a step-down bed at once never
     *  enters the ward's queue, so each patient records its own wait.
     */
    val timeWaiting get() = waitForBed

    private inner class Patient : Entity() {
        val admission: KSLProcess = process(isDefaultProcess = true) {
            val ward = wardBeds.seizeTrigger()
            val stepDown = stepDownBeds.seizeTrigger()
            val result = waitForFirst(ward, stepDown, timeout = patience.value)
            waitForBed.value = result.timeWaited
            if (result.timedOut) {
                overflowed.value = 1.0
                return@process
            }
            overflowed.value = 0.0
            placedInStepDown.value = if (result.firedBy(stepDown)) 1.0 else 0.0
            val bed = if (result.firedBy(ward)) result.valueOf(ward) else result.valueOf(stepDown)
            delay(stay)
            release(bed)
        }
    }

    private val generator = EntityGenerator(::Patient, timeBetweenArrivals, timeBetweenArrivals)
}
```

The step-down unit is a second pool. A patient now waits with `waitForFirst` for whichever comes first: a ward
bed (`wardBeds.seizeTrigger()`), a step-down bed (`stepDownBeds.seizeTrigger()`), or the end of its patience. A
pool's seize trigger joins a wait as a resource's does: the request waits in the pool's queue, and when one
pool serves the patient, its request in the other pool's queue is withdrawn at once. `firedBy` says which bed
it got and `valueOf` hands over that allocation.

The patient records its own wait, from the result's `timeWaited`. A patient who arrives to a full ward and a
free step-down bed takes it at once, without ever joining the ward's queue, so neither queue sees every wait.
The number waiting can still come from the ward's queue: every patient who waits at all waits in both.

#### 4. The comparison

```kotlin
fun main() {
    fun run(build: (Model) -> Unit): Model {
        val m = Model("HospitalBeds", autoCSVReports = false)
        build(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 20_000.0
        m.lengthOfReplicationWarmUp = 2_000.0
        m.simulate()
        return m
    }
    lateinit var ward: Ward
    run { ward = Ward(it, name = "Ward") }
    val exactWard = ErlangA(lambda = 1.4, mu = 1.0 / 8.0, theta = 1.0 / 4.0, c = 12)
    println("The ward alone, 12 beds, against the M/M/12+M answers, 99% confidence intervals")
    println(String.format("%-30s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBeds("probability of overflow", ward.probOverflow.acrossReplicationStatistic, exactWard.probAbandon)
    compareBeds("mean number waiting", ward.numWaiting.acrossReplicationStatistic, exactWard.meanNumberWaiting)
    compareBeds("mean wait, all patients", ward.timeWaiting.acrossReplicationStatistic, exactWard.meanWait)

    lateinit var both: WardWithStepDown
    run { both = WardWithStepDown(it, name = "Ward") }
    val exactBoth = ErlangA(lambda = 1.4, mu = 1.0 / 8.0, theta = 1.0 / 4.0, c = 16)
    println()
    println("The ward with its step-down unit, 12 + 4 beds, against the M/M/16+M answers")
    println(String.format("%-30s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBeds("probability of overflow", both.probOverflow.acrossReplicationStatistic, exactBoth.probAbandon)
    compareBeds("mean number waiting", both.numWaiting.acrossReplicationStatistic, exactBoth.meanNumberWaiting)
    compareBeds("mean wait, all patients", both.timeWaiting.acrossReplicationStatistic, exactBoth.meanWait)
    println(String.format("%-30s %10.4f", "share placed in step-down", both.probStepDown.acrossReplicationStatistic.average))
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 6.")
}

private fun compareBeds(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-30s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
```

### What it shows

```text
The ward alone, 12 beds, against the M/M/12+M answers, 99% confidence intervals
quantity                         estimate               interval      exact   inside
probability of overflow            0.1045       [0.1014, 0.1076]     0.1054      yes
mean number waiting                0.5839       [0.5645, 0.6033]     0.5903      yes
mean wait, all patients            0.4174       [0.4051, 0.4297]     0.4217      yes

The ward with its step-down unit, 12 + 4 beds, against the M/M/16+M answers
quantity                         estimate               interval      exact   inside
probability of overflow            0.0180       [0.0169, 0.0190]     0.0182      yes
mean number waiting                0.1014       [0.0947, 0.1081]     0.1017      yes
mean wait, all patients            0.0725       [0.0679, 0.0770]     0.0726      yes
share placed in step-down          0.1385
```

### How we know it is right

Every estimate's 99% interval contains its exact value. For the ward alone that is the M/M/12+M answer, as in
case 1. For the two units together it is the M/M/16+M answer: every waiting patient waits in both pools' queues
in arrival order, and a bed freed in either pool goes to the earliest of them, so the twelve ward beds and the
four step-down beds act as one pool of sixteen. The agreement checks the pool's seize, its seize trigger, the
withdrawal of the losing request, and the timed wait across two pools at once.

### What to learn

**A pool is one resource for a request any member can serve.** Asking the pool for a bed, with or without a
time limit, behaves as asking one resource of all the members' beds; the rooms matter only to which bed is
chosen.

**Waiting in two pools at once pools them.** The step-down beds cut the overflow from one patient in ten to
fewer than two in a hundred, and the mean wait from 25 minutes to under 5: four more beds, shared through one
wait. About one placed patient in seven gets a step-down bed.

**Record a wait where every patient passes.** A queue's time in queue counts only the requests that entered
it; a patient served at once by another trigger of its wait never does. When a wait has several triggers,
record the wait from the result.

---

## 7. Two colours in one bin

### The problem

A cell takes red and blue parts from one bin. A picker takes one part at a time, whichever colour comes first,
and works on it: its wait is two item triggers on the bin, one for each colour. What does the bin's choice of
request to serve do to such a wait? A picker with a time limit tidies the cell when no part comes. A painter
takes every part of one colour at once.

### The system

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between red parts | exponential, mean 20 minutes | `TimeBetweenRed` |
| Time between blue parts | exponential, mean 30 minutes | `TimeBetweenBlue` |
| Work on a part, or on a batch | exponential, mean 9 minutes | `Work` |
| The bin | one blocking queue, with no limit | `Bin` |
| The bin's request selector | `FirstFillableRequest`, or the default | `firstFillable` |
| How the parts are taken | one of either colour by two triggers; one of any colour by one request; every part of one colour | `picking` |
| The picker's patience | no limit, or 10 minutes | `patience` |
| Tidying | 5 minutes | `tidyTime` |
| Replications | 20 of 100,000 minutes, after a warm-up of 10,000 | |

The parts arrive as one exponential stream at 1/20 + 1/30 = 1/12 a minute, and the picker works on one at a time
whatever its colour. If the picker takes a part whenever the bin holds one, the bin is the queue of an M/M/1
queue with utilization 9/12 = 0.75: on average 0.75²/0.25 = 2.25 parts in the bin, each waiting 2.25 × 12 = 27
minutes.

### The model

```mermaid
flowchart LR
    R["red parts"] --> B[("bin")]
    U["blue parts"] --> B
    B --> W{"first of: a red part,<br/>a blue part,<br/>or 10 minutes"}
    W -- "a part" --> P1["work on it"] --> W
    W -- "10 minutes" --> P2["tidy"] --> W
```

*Figure 6 — the picker's wait: a request for each colour in the bin's request queue, and a time limit.*

### The code

`TwoColourBin.kt` in full.

#### 1. Parts, and the ways of taking them

```kotlin
/** The colour of a part. */
enum class Colour { RED, BLUE }

/** How the bin's parts are taken. */
enum class Picking {
    /** One part at a time, the first of a red part or a blue part: two item triggers in one wait. */
    TWO_TRIGGERS,
    /** One part at a time, of either colour: one request for any part. */
    ONE_REQUEST,
    /** Every part of one colour at once, the first colour to come: two any-items triggers in one wait. */
    BATCHES
}
```

#### 2. The bin

```kotlin
class TwoColourBin(
    parent: ModelElement,
    val picking: Picking = Picking.TWO_TRIGGERS,
    firstFillable: Boolean = true,
    val meanBetweenRed: Double = 20.0,
    val meanBetweenBlue: Double = 30.0,
    val meanWork: Double = 9.0,
    val patience: Double = Double.POSITIVE_INFINITY,
    val tidyTime: Double = 5.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenRed = RandomVariable(this, ExponentialRV(meanBetweenRed, 1), "TimeBetweenRed")
    private val timeBetweenBlue = RandomVariable(this, ExponentialRV(meanBetweenBlue, 2), "TimeBetweenBlue")
    private val work = RandomVariable(this, ExponentialRV(meanWork, 3), "Work")

    private inner class Part(val colour: Colour) : QObject()

    private val bin: BlockingQueue<Part> = BlockingQueue(this, name = "Bin")

    init {
        if (firstFillable) bin.receiverRequestSelector = bin.FirstFillableRequest()
    }

    /** The time-average number of parts in the bin. */
    val numInBin get() = bin.channelQ.numInQ
    /** The time parts wait in the bin. */
    val timeInBin get() = bin.channelQ.timeInQ

    /** The parts taken from the bin. */
    val partsTaken = Counter(this, "PartsTaken")
    /** The times the picker tidied the cell. */
    val timesTidied = Counter(this, "TimesTidied")
    /** The parts that arrived and were neither taken nor left in the bin, each replication: none. */
    val unaccounted = Response(this, "PartsUnaccounted")
    /** The batches that left a part of their colour in the bin, each replication: none. */
    val incompleteBatches = Response(this, "IncompleteBatches")
    /** The number of parts in a batch. */
    val batchSize = Response(this, "BatchSize")

    // counts by colour kept from the start of the replication, for the accounting at its end
    private val arrived = IntArray(Colour.entries.size)
    private val taken = IntArray(Colour.entries.size)
    private var incomplete = 0

    /** The parts of [colour] that should be in the bin, by the model's own counts. */
    private fun inBin(colour: Colour) = arrived[colour.ordinal] - taken[colour.ordinal]
```

The bin is one blocking queue. `FirstFillableRequest` is an inner class of the queue, so it is made from the bin
itself. The accounting keeps the model's own count of each colour, from the start of the replication.

#### 3. The suppliers

```kotlin
    private inner class Supplier(val colour: Colour, val timeBetween: RandomVariable) : Entity() {
        val supply: KSLProcess = process {
            while (true) {
                delay(timeBetween)
                arrived[colour.ordinal]++
                send(Part(colour), bin)
            }
        }
    }
```

#### 4. The picker: two triggers in one wait

```kotlin
    private fun took(parts: List<Part>) {
        for (p in parts) taken[p.colour.ordinal]++
        partsTaken.increment(parts.size.toDouble())
    }

    private inner class Picker : Entity() {
        val picking: KSLProcess = process {
            while (true) {
                val red = bin.itemsTrigger(predicate = { it.colour == Colour.RED })
                val blue = bin.itemsTrigger(predicate = { it.colour == Colour.BLUE })
                val result = waitForFirst(red, blue, timeout = patience)
                if (result.timedOut) {
                    timesTidied.increment()
                    delay(tidyTime)
                    continue
                }
                took(if (result.firedBy(red)) result.valueOf(red) else result.valueOf(blue))
                delay(work)
            }
        }
    }
```

`bin.itemsTrigger(predicate = …)` is a trigger for one part that matches. The two triggers place two requests in
the bin's request queue for the one wait; the first to be filled ends it, and the other request leaves the
queue. With no patience, `timeout` is `Double.POSITIVE_INFINITY` and the wait has no limit.

#### 5. The same picker, with one request

```kotlin
    private inner class AnyPartPicker : Entity() {
        val picking: KSLProcess = process {
            while (true) {
                val result = waitForItemsOrTimeout(bin, timeout = patience)
                if (result.timedOut) {
                    timesTidied.increment()
                    delay(tidyTime)
                    continue
                }
                took(result.value)
                delay(work)
            }
        }
    }
```

When any part will do, one request for any part is the same picker: `waitForItemsOrTimeout` is the timed wait
for items, and its result is satisfied with the items or timed out.

#### 6. The painter: every part of one colour

```kotlin
    private inner class Painter : Entity() {
        val painting: KSLProcess = process {
            while (true) {
                val red = bin.anyItemsTrigger(predicate = { it.colour == Colour.RED })
                val blue = bin.anyItemsTrigger(predicate = { it.colour == Colour.BLUE })
                val result = waitForFirst(red, blue)
                val batch = if (result.firedBy(red)) result.valueOf(red) else result.valueOf(blue)
                took(batch)
                // the batch took every part of its colour: none of it is left
                if (inBin(batch.first().colour) != 0) incomplete++
                batchSize.value = batch.size.toDouble()
                delay(work)
            }
        }
    }
```

`bin.anyItemsTrigger(predicate = …)` takes every part that matches, at least one. The painter checks against the
model's own count that a batch leaves none of its colour behind.

#### 7. Starting, and the accounting

```kotlin
    override fun initialize() {
        arrived.fill(0)
        taken.fill(0)
        incomplete = 0
        activate(Supplier(Colour.RED, timeBetweenRed).supply)
        activate(Supplier(Colour.BLUE, timeBetweenBlue).supply)
        when (picking) {
            Picking.TWO_TRIGGERS -> activate(Picker().picking)
            Picking.ONE_REQUEST -> activate(AnyPartPicker().picking)
            Picking.BATCHES -> activate(Painter().painting)
        }
    }

    override fun replicationEnded() {
        unaccounted.value = (Colour.entries.sumOf { inBin(it) } - bin.channelQ.size).toDouble()
        incompleteBatches.value = incomplete.toDouble()
    }
}
```

#### 8. Running it

```kotlin
fun main() {
    fun run(build: (Model) -> TwoColourBin): TwoColourBin {
        val m = Model("TwoColourBin", autoCSVReports = false)
        val b = build(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 100_000.0
        m.lengthOfReplicationWarmUp = 10_000.0
        m.simulate()
        return b
    }
    val first = run { TwoColourBin(it, name = "Cell") }
    // one server, the picker, for one stream of parts at the two colours' combined rate
    val lambda = 1.0 / first.meanBetweenRed + 1.0 / first.meanBetweenBlue
    val rho = lambda * first.meanWork
    val exactNumber = rho * rho / (1.0 - rho)
    val exactTime = exactNumber / lambda
    println("A part of either colour, FirstFillableRequest, against the exact M/M/1 values, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBin("mean parts in the bin", first.numInBin.acrossReplicationStatistic, exactNumber)
    compareBin("mean time in the bin", first.timeInBin.acrossReplicationStatistic, exactTime)

    val head = run { TwoColourBin(it, firstFillable = false, name = "Cell") }
    println()
    println("The same with the default selector, which serves only the request at the head")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBin("mean parts in the bin", head.numInBin.acrossReplicationStatistic, exactNumber)
    compareBin("mean time in the bin", head.timeInBin.acrossReplicationStatistic, exactTime)

    val twoTriggers = run { TwoColourBin(it, patience = 10.0, name = "Cell") }
    val oneRequest = run { TwoColourBin(it, picking = Picking.ONE_REQUEST, patience = 10.0, name = "Cell") }
    println()
    println("Waiting at most 10 minutes for a part, then tidying for 5: two triggers against one request")
    println(String.format("%-28s %14s %14s %6s", "statistic", "two triggers", "one request", "same"))
    for ((label, a, b) in listOf(
        Triple("mean parts in the bin", twoTriggers.numInBin.acrossReplicationStatistic, oneRequest.numInBin.acrossReplicationStatistic),
        Triple("parts taken", twoTriggers.partsTaken.acrossReplicationStatistic, oneRequest.partsTaken.acrossReplicationStatistic),
        Triple("times tidied", twoTriggers.timesTidied.acrossReplicationStatistic, oneRequest.timesTidied.acrossReplicationStatistic)
    )) {
        val same = a.average == b.average && a.variance == b.variance
        println(String.format("%-28s %14.4f %14.4f %6s", label, a.average, b.average, if (same) "yes" else "NO"))
    }

    val painter = run { TwoColourBin(it, picking = Picking.BATCHES, name = "Cell") }
    println()
    println("A painter taking every part of one colour at once")
    println(String.format("%-28s %10.4f", "mean batch size", painter.batchSize.acrossReplicationStatistic.average))
    println(String.format("%-28s %10.4f", "mean parts in the bin", painter.numInBin.acrossReplicationStatistic.average))
    println(String.format("%-28s %10.0f", "most parts unaccounted", painter.unaccounted.acrossReplicationStatistic.max))
    println(String.format("%-28s %10.0f", "most incomplete batches", painter.incompleteBatches.acrossReplicationStatistic.max))
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 7.")
}

private fun compareBin(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
```

### What it shows

```text
A part of either colour, FirstFillableRequest, against the exact M/M/1 values, 99% confidence intervals
quantity                       estimate               interval      exact   inside
mean parts in the bin            2.2696       [2.1051, 2.4341]     2.2500      yes
mean time in the bin            27.2364     [25.3725, 29.1002]    27.0000      yes

The same with the default selector, which serves only the request at the head
quantity                       estimate               interval      exact   inside
mean parts in the bin            2.9287       [2.7611, 3.0963]     2.2500       NO
mean time in the bin            35.1450     [33.2697, 37.0202]    27.0000       NO

Waiting at most 10 minutes for a part, then tidying for 5: two triggers against one request
statistic                      two triggers    one request   same
mean parts in the bin                2.3212         2.3212    yes
parts taken                       7496.3000      7496.3000    yes
times tidied                      1096.4000      1096.4000    yes

A painter taking every part of one colour at once
mean batch size                  1.2401
mean parts in the bin            0.5902
most parts unaccounted                0
most incomplete batches               0
```

### How we know it is right

With `FirstFillableRequest` both 99% intervals contain their exact values. With a time limit, the two triggers
and the one request agree exactly in the parts in the bin, the parts taken and the times tidied: they draw the
same random numbers and take a part at the same moments. Which part they take can differ, so the time each part
waits is not compared. The painter accounts for every part, taken or still in the bin, in every replication, and
no batch leaves a part of its colour behind.

### What to learn

**Two triggers on one queue are two requests, and the default selector serves only the first.** The picker's red
request is placed first, so it heads the bin's request queue. A blue part that arrives while the picker waits is
not given to it: the request at the head cannot be filled, so none is. The picker sits idle beside the blue part
until a red one comes. Only at the start of a wait is a part the bin already holds taken whatever its colour. The
bin holds 2.93 parts instead of 2.25, and the check against M/M/1 fails, as it should.
`bin.receiverRequestSelector = bin.FirstFillableRequest()` serves the first request the bin can fill.

**When any part will do, ask for any part.** One request has no rival in the request queue, so the selector does
not matter to it. Two triggers earn their place when what the picker does next depends on which one fired.

**A time limit is one more way out of the same wait.** `waitForFirst(red, blue, timeout = patience)` and
`waitForItemsOrTimeout(bin, timeout = patience)` end the same way when the time runs out: nothing is taken, and the
requests leave the queue.

**`anyItemsTrigger` takes every part that matches.** The painter takes 1.24 parts a batch on average, and painting
whole batches keeps 0.59 parts in the bin.

---

## 8. Timed auctions

### The problem

An auctioneer sells one lot an hour. Bidders arrive and bid, and each waits for the first of three things: the
lot is awarded to it, the auction closes without it, or its patience runs out and it withdraws its bid. The
bidders are agents (see [`ksl-agent`](ksl-agent.md)): the award is a message in a bidder's mailbox, and the close
is a broadcast to every bidder still waiting. How often is a lot sold, for how much, and how long do bidders wait?

### The system

| Quantity | Distribution or value | Model element |
|---|---|---|
| Time between bidders | exponential, mean 10 minutes | the bidders' generator |
| Bid | uniform, 0 to 100 | `BidAmount` |
| A bidder's patience | exponential, mean 30 minutes | `Patience` |
| Auction | one lot, closing every 60 minutes; the highest bid standing wins | `auctionLength` |
| How the losers are told | a broadcast, `AgentSignal`, or a `Reject` to each | `closing` |
| Replications | 20 of 1,000 auctions | `numAuctions` |

The answers are exact. A bidder arrives at a time uniform over its auction, so the time to the close is uniform
from 0 to 60 minutes, and the bidder is still bidding at the close with probability (1 − e^−2)/2 = 0.432. The
bidders still bidding at a close are Poisson with mean 6 × 0.432 = 2.59, so a lot is sold with probability
1 − e^−2.59 = 0.925. Given n bids, the winning one has mean 100 n/(n + 1). A bidder waits for its patience or the
close, whichever is shorter. `AuctionExact` works these out.

### The model

```mermaid
flowchart LR
    A(["bidder arrives"]) --> B["bids"] --> W{"first of: the award,<br/>the close,<br/>or its patience"}
    W -- award --> X(["won"])
    W -- close --> Y(["lost"])
    W -- patience --> Z["withdraws its bid"] --> V(["withdrew"])
    C["every 60 minutes"] --> D["award the highest bid,<br/>then broadcast the close"]
```

*Figure 7 — a bidder's wait, with three ways out, and the auctioneer's close.*

### The code

`TimedAuction.kt` in full.

#### 1. Two ways to tell the losers

```kotlin
enum class Closing {
    /** The winner gets an `Accept`; the others hear the close broadcast, an agent signal. */
    SIGNAL,
    /** The winner gets an `Accept`; every other bidder gets a `Reject`. */
    REJECTIONS
}
```

#### 2. The auctions

```kotlin
class TimedAuction(
    parent: ModelElement,
    val closing: Closing = Closing.SIGNAL,
    val meanBetweenBidders: Double = 10.0,
    val meanPatience: Double = 30.0,
    val auctionLength: Double = 60.0,
    val numAuctions: Int = 1000,
    name: String? = null
) : AgentModel(parent, name) {

    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val bidAmount = RandomVariable(this, UniformRV(0.0, 100.0, 3), "BidAmount")

    private val auctioneer = PermanentAgent("Auctioneer")
    private val closed = AgentSignal("AuctionClosed")

    private class Bid(val bidder: Bidder, val amount: Double)

    private val book = mutableListOf<Bid>()
    private var lot = 0

    /** Whether each auction sold its lot. */
    val probSold = Response(this, "ProbSold")
    /** The amount of each winning bid. */
    val winningBid = Response(this, "WinningBid")
    /** Whether each bidder won. */
    val probWon = Response(this, "ProbWon")
    /** Whether each bidder withdrew before the close. */
    val probWithdrew = Response(this, "ProbWithdrew")
    /** The time each bidder waited, from its bid to the end of its wait. */
    val timeWaiting = Response(this, "TimeWaiting")
    /** The bidders without exactly one outcome, and the lots sold without exactly one winner, each replication: none. */
    val unaccounted = Response(this, "Unaccounted")

    // counts kept from the start of the replication, for the accounting at its end
    private var arrived = 0
    private var resolved = 0
    private var won = 0
    private var sold = 0
```

The model is an `AgentModel`, the process model of the agent layer. The auctioneer is a permanent agent, so that
its messages have a sender. The book is the list of bids standing.

#### 3. The bidder

```kotlin
    inner class Bidder(aName: String) : Agent(aName) {
        val bidding: KSLProcess = process(isDefaultProcess = true) {
            val bid = Bid(this@Bidder, bidAmount.value)
            book.add(bid)
            val limit = patience.value
            val outcome: Outcome
            val waited: Double
            when (closing) {
                Closing.SIGNAL -> {
                    val award = mailbox.triggerOfType<AgentMessage.Accept>()
                    val close = closed.trigger()
                    val result = waitForFirst(award, close, timeout = limit)
                    waited = result.timeWaited
                    outcome = when (result.trigger) {
                        award -> Outcome.WON
                        close -> Outcome.LOST
                        else -> Outcome.WITHDREW
                    }
                }
                Closing.REJECTIONS -> {
                    val result = receiveMessageOrTimeout(mailbox, timeout = limit)
                    waited = result.timeWaited
                    outcome = when {
                        result.timedOut -> Outcome.WITHDREW
                        result.value is AgentMessage.Accept -> Outcome.WON
                        else -> Outcome.LOST
                    }
                }
            }
            if (outcome == Outcome.WITHDREW) book.remove(bid)
            if (outcome == Outcome.WON) won++
            resolved++
            probWon.value = if (outcome == Outcome.WON) 1.0 else 0.0
            probWithdrew.value = if (outcome == Outcome.WITHDREW) 1.0 else 0.0
            timeWaiting.value = waited
        }
    }

    private enum class Outcome { WON, LOST, WITHDREW }
```

With the broadcast, the bidder's wait is one call with three ways out. `mailbox.triggerOfType<AgentMessage.Accept>()`
is a trigger for an `Accept` message in the bidder's mailbox; `closed.trigger()` is a trigger for the next firing
of the auction's `AgentSignal`; the timeout is its patience. With rejections, every bidder who does not win gets a
message, so one `receiveMessageOrTimeout` is enough, and the message's type says how the auction went. Either way a
bidder who runs out of patience takes its bid out of the book.

#### 4. Arrivals and closes

```kotlin
    private var created = 0

    private val bidders = AgentGenerator(
        agentFactory = { arrived++; Bidder("Bidder-${++created}") },
        timeUntilFirst = ExponentialRV(meanBetweenBidders, 1),
        timeBetween = ExponentialRV(meanBetweenBidders, 1),
        timeOfLast = numAuctions * auctionLength
    )

    override fun initialize() {
        book.clear()
        lot = 0
        arrived = 0
        resolved = 0
        won = 0
        sold = 0
        schedule(::close, auctionLength)
    }

    private fun close(event: KSLEvent<Nothing>) {
        lot++
        val winner = book.maxByOrNull { it.amount }
        probSold.value = if (winner == null) 0.0 else 1.0
        if (winner != null) {
            sold++
            winningBid.value = winner.amount
            winner.bidder.mailbox.deliver(AgentMessage.Accept(auctioneer, "lot-$lot"))
        }
        when (closing) {
            Closing.SIGNAL -> closed.fire()
            Closing.REJECTIONS -> for (b in book) if (b !== winner) b.bidder.mailbox.deliver(AgentMessage.Reject(auctioneer, "lot-$lot"))
        }
        book.clear()
        if (lot < numAuctions) schedule(::close, auctionLength)
    }

    override fun replicationEnded() {
        unaccounted.value = (abs(arrived - resolved) + abs(sold - won)).toDouble()
    }
}
```

At a close the auctioneer delivers the award first, then tells the others. The winner's wait is already ended by
the award when the close is broadcast, so the broadcast does not reach it as well. The generator stops at the last
close, so that every bidder's wait ends within the run.

#### 5. The exact answers

```kotlin
class AuctionExact(val lambda: Double, val theta: Double, val length: Double, val top: Double = 100.0) {
    /** The probability that a bidder is still bidding at the close. */
    val probStanding: Double = (1.0 - exp(-theta * length)) / (theta * length)
    /** The mean number of bidders still bidding at a close. */
    val standing: Double = lambda * length * probStanding
    /** The probability that an auction sells its lot. */
    val probSold: Double = 1.0 - exp(-standing)
    /** The probability that a bidder wins: one winner per lot sold, among the bidders of an auction. */
    val probWon: Double = probSold / (lambda * length)
    /** The probability that a bidder withdraws before the close. */
    val probWithdrew: Double = 1.0 - probStanding
    /** The mean time a bidder waits: its patience or the time to the close, whichever is shorter. */
    val meanWait: Double = probWithdrew / theta

    /** The mean winning bid: the largest of n uniform bids has mean n/(n + 1) of the top, given a sale. */
    val meanWinningBid: Double
        get() {
            var p = exp(-standing)
            var sum = 0.0
            var n = 0
            while (n < 200) {
                n++
                p *= standing / n
                sum += p * n / (n + 1.0)
            }
            return top * sum / probSold
        }
}
```

#### 6. Running it

```kotlin
fun main() {
    fun run(closing: Closing): TimedAuction {
        val m = Model("TimedAuction", autoCSVReports = false)
        val a = TimedAuction(m, closing = closing, name = "Auction")
        m.numberOfReplications = 20
        // a moment past the last close, so that every bidder's wait has ended
        m.lengthOfReplication = a.numAuctions * a.auctionLength + 1.0
        m.simulate()
        return a
    }
    val bySignal = run(Closing.SIGNAL)
    val exact = AuctionExact(1.0 / bySignal.meanBetweenBidders, 1.0 / bySignal.meanPatience, bySignal.auctionLength)
    println("Bidders told of the close by a broadcast, against the exact values, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareAuction("probability a lot is sold", bySignal.probSold.acrossReplicationStatistic, exact.probSold)
    compareAuction("mean winning bid", bySignal.winningBid.acrossReplicationStatistic, exact.meanWinningBid)
    compareAuction("probability a bidder wins", bySignal.probWon.acrossReplicationStatistic, exact.probWon)
    compareAuction("probability of withdrawing", bySignal.probWithdrew.acrossReplicationStatistic, exact.probWithdrew)
    compareAuction("mean wait of a bidder", bySignal.timeWaiting.acrossReplicationStatistic, exact.meanWait)
    println(String.format("%-28s %10.0f", "most unaccounted", bySignal.unaccounted.acrossReplicationStatistic.max))

    val byRejections = run(Closing.REJECTIONS)
    println()
    println("The same auctions, with a rejection for each losing bidder and receiveMessageOrTimeout")
    println(String.format("%-28s %12s %12s %6s", "statistic", "broadcast", "rejections", "same"))
    for ((label, a, b) in listOf(
        Triple("probability a lot is sold", bySignal.probSold, byRejections.probSold),
        Triple("mean winning bid", bySignal.winningBid, byRejections.winningBid),
        Triple("probability a bidder wins", bySignal.probWon, byRejections.probWon),
        Triple("probability of withdrawing", bySignal.probWithdrew, byRejections.probWithdrew),
        Triple("mean wait of a bidder", bySignal.timeWaiting, byRejections.timeWaiting)
    )) {
        val x = a.acrossReplicationStatistic
        val y = b.acrossReplicationStatistic
        val same = x.average == y.average && x.variance == y.variance
        println(String.format("%-28s %12.4f %12.4f %6s", label, x.average, y.average, if (same) "yes" else "NO"))
    }
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 8.")
}

private fun compareAuction(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
```

### What it shows

```text
Bidders told of the close by a broadcast, against the exact values, 99% confidence intervals
quantity                       estimate               interval      exact   inside
probability a lot is sold        0.9271       [0.9230, 0.9312]     0.9253      yes
mean winning bid                69.3670     [68.8599, 69.8741]    69.5249      yes
probability a bidder wins        0.1545       [0.1533, 0.1557]     0.1542      yes
probability of withdrawing       0.5659       [0.5614, 0.5704]     0.5677      yes
mean wait of a bidder           17.0462     [16.9195, 17.1729]    17.0300      yes
most unaccounted                      0

The same auctions, with a rejection for each losing bidder and receiveMessageOrTimeout
statistic                       broadcast   rejections   same
probability a lot is sold          0.9271       0.9271    yes
mean winning bid                  69.3670      69.3670    yes
probability a bidder wins          0.1545       0.1545    yes
probability of withdrawing         0.5659       0.5659    yes
mean wait of a bidder             17.0462      17.0462    yes
```

### How we know it is right

Each 99% interval contains its exact value. In every replication every bidder ended its wait exactly one way, and
every lot sold had exactly one winner: a winner whose close trigger also fired would be counted as a loser, and the
accounting would show it. The two ways of telling the losers agree exactly, in every statistic: they draw the same
random numbers, and each bidder's wait ends at the same moment.

### What to learn

**An agent's message and an agent's signal join a wait like any other trigger.** `mailbox.triggerOfType<T>()`
waits for a message of one type and leaves the others in the mailbox; `AgentSignal.trigger()` waits for the next
firing. A signal has no memory: a bidder that starts waiting after the close does not hear it.

**One firing reaches every waiter.** The broadcast ends the waits of all the losing bidders at once; with
rejections, the auctioneer sends one message to each.

**Order the outcomes at an instant on purpose.** The award is delivered before the close is broadcast, so the
winner's wait ends with the award. Delivered the other way round, the winner would hear the close first and be
counted as a loser.

**A withdrawal is the model's job.** A timed-out wait takes nothing and sends nothing; the bidder removes its own
bid from the book.

---

## Checking your own model

- **Compare with an answer found another way.** Every case here does. A queueing model with known answers
  (cases 1, 3, 6 and 7), an exact probability model (case 8), the same system written with events and `removeAndTerminate` (case 2), or another formulation (case 4) catch
  mistakes no amount of reading the code will.
- **Account for everything.** Conservation (cases 5, 7 and 8) and Little's law (case 1) are checks a model can make
  of itself, every replication.
- **Turn on the resource checks.** Run with `-Dksl.checkInvariants=true` and every resource checks its
  accounting after each event: units busy, units held for reservations, and the waits registered on
  allocations. A timed seize that left a request behind would be caught at the event that did it.
- **Look at the animation.** A wait that times out appears in the animation trace as `WaitTimedOut`. A
  seize request that leaves its queue without being served, because its time ran out or something else
  the entity waited for came first, appears as `SeizeWithdrawn`.

## Pitfalls

- **A signal has no memory** (cases 5 and 8). Send it when someone is waiting, or use a structure that keeps it.
- **Count those who give up yourself,** from `timedOut` or `result.trigger == null`. Nothing counts them
  for you.
- **`recordRenegedWait` changes what time in queue means** (case 1). Decide which you want, and report
  which you used.
- **`valueOf` throws for a trigger that did not end the wait.** Ask `firedBy` first, or use `when` on
  `result.trigger`.
- **End a timed wait, or a wait for the first of several things, through what it waits on.**
  `resumeProcess()` throws for an entity in such a wait, even one with no time limit; check
  `canResumeProcess` first. Resume its suspension, signal its signal, send to its queue.
- **The order of the triggers settles only a tie at the start of the wait** (case 3). Later ties go by the
  calendar (case 5).
- **Two item triggers on one blocking queue need `FirstFillableRequest`** (case 7). Under the default
  selector the request at the head holds back the other, even one of the same wait.
- **A negative timeout throws;** `Double.POSITIVE_INFINITY` means no limit.

---

## Appendix A — the companion files and how to run them

| Case | File, in `KSLExamples/src/main/kotlin/ksl/examples/general/processview/waits/` | Gradle task |
|---|---|---|
| 1 | `ImpatientCallers.kt` | `:KSLExamples:impatientCallers` |
| 2 | `WalkInHealthClinicViaTimedSeize.kt` | `:KSLExamples:walkInClinicTwoWays` |
| 3 | `FirstFreeOfTwo.kt` | `:KSLExamples:firstFreeOfTwo` |
| 4 | `ShuttleStop.kt` | `:KSLExamples:shuttleStop` |
| 5 | `KittingWithExpediting.kt` | `:KSLExamples:kittingWithExpediting` |
| 6 | `HospitalBeds.kt` | `:KSLExamples:hospitalBeds` |
| 7 | `TwoColourBin.kt` | `:KSLExamples:twoColourBin` |
| 8 | `TimedAuction.kt` | `:KSLExamples:timedAuction` |

The checks of this page are in `KSLExamples/src/test/kotlin/ksl/examples/general/processview/waits/`.

Case 8 waits on agent messages and an agent signal. For more of `receiveMessageOrTimeout`, see the job shop and
warehouse examples of [`ksl-agent`](ksl-agent.md), whose bidders stop waiting for a decision that will not come.
