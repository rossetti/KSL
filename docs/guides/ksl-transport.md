# Moving things with vehicles in KSL

*An overview.* Which package models what, and how to choose. Each cell
below links to the guide that covers it.

KSL has four ways to move a load on a vehicle. They are not four points on
a scale, and reading them as one is the usual source of confusion. They
are **two independent questions**, each with two answers.

---

## The two questions

### 1. Does space push back?

- **Free path.** A vehicle travels straight to where it is going, in
  `distance / velocity` time. Two vehicles may occupy the same corridor.
  Nothing waits for anything. This is the right model for a fork-lift in
  an open warehouse, a porter crossing a hospital, a van on a road
  network: they queue for *work*, never for *aisles*.
- **Guide path.** The layout is divided into zones; a zone holds one
  vehicle; a vehicle claims the space ahead before it moves into it. An
  aisle holds one cart, a one-way loop cannot be run backwards, and a
  vehicle parked at the end of a spur is in the way of everything behind
  it. Blocking is not an error here — it is the phenomenon you came to
  measure.

### 2. Who decides which vehicle goes?

- **Passive.** The entity holds the protocol: it asks a pool for a
  vehicle, waits, rides, and hands it back. The choice of *which* vehicle
  is made inside the entity's own process, at the instant it happens to
  ask, over whatever is free at that instant. There is nowhere else it
  could be made, because no other object is running.
- **Active.** A **dispatcher** decides. It can see the whole fleet and the
  whole board, and it is allowed to consume simulated time deciding. That
  last clause is what makes batching, negotiation, re-tasking in flight,
  consolidating several loads onto one vehicle, and fixed routes
  expressible at all.

---

## The four cells

|  | **Free path**<br>*nothing blocks* | **Guide path**<br>*aisles push back* |
|---|---|---|
| **Passive**<br>*the entity seizes* | `MovableResource`, `MovableResourcePoolWithQ`<br>`move` / `moveWith` / `transportWith`<br>→ [`ksl-spatial`](ksl-spatial.md) | `GuidedTransporter`, `GuidedTransporterPoolWithQ`<br>`guidedTransport`<br>→ [`ksl-guidedpath`](ksl-guidedpath.md) |
| **Active**<br>*a dispatcher tasks* | `FreePathFleet`, `FreePathVehicle`<br>`transportByFleet`, `rideFrom`<br>→ [`ksl-fleet` §4](ksl-fleet.md#run-a-fleet-without-a-guide-path) | `AgvSystem`, `AgvVehicle`<br>`transportByFleet`, `rideFrom`<br>→ [`ksl-fleet`](ksl-fleet.md) |

The bottom row is one package. `ksl.modeling.fleet` is the dispatcher,
tasks, tours, stops, lines, the manifest, the policies and every
statistic, and it names no substrate. `ksl.modeling.agv` is its
guide-path binding; `FreePathFleet` is its free-path binding. Everything
in [`ksl-fleet`](ksl-fleet.md) is true of both unless it says otherwise.

---

## Choosing

**The substrate is a modelling question with a measurable answer.** It is
not a matter of fidelity or taste. Free path and guide path give the same
answer while the fleet is small enough that vehicles rarely meet, and
diverge once they do — and the divergence is not a rounding error.
[`ksl-guidedpath` §1](ksl-guidedpath.md#1-what-this-package-is-for)
measures it on one haul: identical at one and two carts, and by eight
carts the free-path model predicts a time in system of 31 where the guide
path delivers 538, because the exit spur admits one cart at a time and no
size of fleet changes that.

The point is not that the free-path number is wrong. It is that nothing in
a free-path model is *capable* of being wrong there: no statistic it
reports, however carefully read, could reveal the aisle it does not
represent. So:

- Vehicles contend for space, and that contention could plausibly bind →
  **guide path**.
- They do not, or the fleet is small relative to the layout → **free
  path**, which costs you no network to build.
- Not sure → build the free-path model first, then check whether the
  answer survives the aisles. That comparison is cheap now, because the
  fleet layer is the same code on both.

**The paradigm is a question about where a decision lives.** Ask whether
any of these is part of your answer:

| | Passive | Active |
|---|---|---|
| "Send the nearest free vehicle" | yes | yes |
| Wait ten minutes and allocate over everything that accumulated | no | yes |
| Let each vehicle bid from what *it* knows about itself | no | yes |
| Take a task back from a vehicle three-quarters of the way there | no | yes |
| Fill one vehicle with several loads, and choose the order of stops | no | yes |
| Run a fixed route that carries whoever is waiting | no | yes |

Each "no" is not a matter of difficulty. Under the passive paradigm there
is nowhere to put the decision, because no object other than the asking
entity is running.

If your rule is "send the nearest free vehicle" and you are content for it
to be evaluated the moment an entity asks, the passive subsystem is
simpler, has fewer moving parts, and gives the same answer. With one
vehicle the two paradigms agree **exactly, to the digit** on a guide path
(`ksl.examples.general.agv.TwoParadigmsExample`), which is what makes them
two models of one world rather than two worlds.

---

## What all four share

**Named places.** Every one of them is written in terms of named
locations, and asks the space how far apart two of them are. A
`DistancesModel` names them and maintains a table; a guide path names
intersections and stations and measures along the aisles; a plane names
the points you give it and measures across.

**Distance is along the path, never straight-line separation**, wherever
the two differ. On a one-way loop a vehicle standing a few feet past a
pickup must go all the way round, and a rule that scored its proximity
would win it the job it is furthest from.

**Two odometers on every vehicle.** `distanceTravelled` and
`operatingTime`, reset each replication, counting every journey however it
was commanded. They are what a wear, service or battery model is computed
from.

**The movement seam.** `VehicleMovementIfc` is what a fleet needs from
whatever moves its vehicles — eight members, no more. `MovableResource`,
`GuidedTransporter` and the agent layer's `MovableAgentResource` all
implement it, and all three are held to the same eight-test conformance
suite (`VehicleMovementConformance`). That is why the bottom row of the
table above is one body of code with two bindings rather than two
subsystems.

---

## The agent layer's projection: geometry yes, agency no

A dispatcher over an agent-based **continuous projection** is half
available, and the halves are worth separating because a modeller needs
only one of them at a time.

**The geometry works today, with no new class.**
`ProjectionSpatialModel` wraps a `ContinuousProjection` as an ordinary
`SpatialModel`, and `FreePathFleet` asks a spatial model for named places
and distances and nothing else. So the dispatcher, tours, consolidation,
stops, lines and every statistic run over projection coordinates:

```kotlin
val space = ProjectionSpatialModel(floor.projection)
val places = listOf(space.location(0.0, 0.0, "Store"), space.location(100.0, 0.0, "WardA"))
val fleet = FreePathFleet(this, space, places, name = "Porters")
```

`FleetOverProjectionTest` runs exactly that.

**What is missing is a vehicle that is itself an agent in the
projection.** The vehicle above is a `FreePathVehicle`, whose body is a
`MovableResource`. It is *at* projection coordinates but not *in* the
projection: it has no `Agent` identity, so neighbour queries, force
dynamics and statecharts cannot see it, and pedestrians walk through it.
Closing that would take a binding built on `MovableAgentResource` —
which does implement the movement seam and does pass the conformance
suite — and nothing ships one.

So: a dispatcher over projection *coordinates* works now. Vehicles that
the crowd can see is unbuilt. If you need the second, drive them with the
agent layer's own verbs for the moment; see [`ksl-agent`](ksl-agent.md).

---

## See also

- [`ksl-transport-tutorial`](ksl-transport-tutorial.md) — the same material by
  worked example: ten cases, each with its problem, a figure of the network it
  models, its complete code explained part by part, what it measured and what
  that is evidence for. **The place to go after this page.**
- [`ksl-spatial`](ksl-spatial.md) — the substrate: locations, distances,
  spatial models, `MovableResource`, and the movement seam.
- [`ksl-guidedpath`](ksl-guidedpath.md) — zones, links, blocking,
  routing, zone control, deadlock. **Read this before either fleet
  subsystem if your vehicles contend for space.**
- [`ksl-fleet`](ksl-fleet.md) — the dispatcher, tours, stops, lines,
  multi-load, batteries and breakdowns, over either substrate.
- [`ksl-entity`](ksl-entity.md) — the process view, and where all of these
  verbs sit among `KSLProcessBuilder`'s others.
- [`ksl-agent`](ksl-agent.md) — the agent framework the active fleet's
  vehicles and dispatcher are built on.

**Worked examples**

| Cell | Example |
|---|---|
| Passive, free path | `book.chapter8.TestAndRepairShopWithMovableResources` |
| Passive, guide path | `book.chapter8.TestAndRepairShopWithGuidedTransporters` |
| Active, free path | `general.fleet.FreePathFleetExample` |
| Active, guide path | `general.agv.TwoParadigmsExample` — the same shop both ways |
