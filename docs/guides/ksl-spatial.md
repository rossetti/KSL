# Using `ksl.modeling.spatial`

A task-oriented usage guide. For each common task, the smallest amount
of code that does it, and the gotchas that matter in practice.
Reference detail (parameter lists, every overload) is on the Dokka API
pages; this guide gets you productive.

## 1. What this package is for

`ksl.modeling.spatial` is KSL's **spatial substrate**: locations,
distances, and the trackable elements that have a position. It supplies
four ready-made `SpatialModel` implementations (graph-of-distances,
Cartesian plane, lattice grid, lat/lon great-circle) plus the
`MovableResource` family — seizable resources that have a position and
can move.

It is **not** itself the movement engine. The suspending verbs that
move things over simulated time (`moveTo`, `move`) live in
`ksl.modeling.entity` on `KSLProcessBuilder`. This package supplies the
*topology* and the *trackable elements* those verbs operate on; the
entity package supplies the *scheduling* that turns "go from A to B at
velocity v" into a delay event.

Reach for it whenever a model needs distance-based delays — workers
walking between stations, forklifts shuttling pallets, delivery
vehicles traversing a road network, planes between airports. Reach for
something else when no distance is involved (process timing only) or
when the spatial model is the *whole* point of the model:

- `ksl.modeling.station` queueing networks happily use this package's
  `MovableResourceWithQ` for movable-server stations.
- `ksl.modeling.agent` carries a *parallel* spatial system aimed at
  agent-based models (statechart-reactive, force-based dynamics, 3D
  variants). The two interoperate via `SpatialBridge` when an agent
  model wants to share coordinates with the process view.

### How it relates to its neighbors

- `ksl.modeling.entity` — the home of `moveTo` / `move`. Movable
  resources live here but are used from entity-side `process { … }`
  bodies through the same `seize` / `release` API as any other
  resource.
- `ksl.modeling.station` — the queueing-network view; uses
  `MovableResourceWithQ` for stations that move between locations.
- `ksl.modeling.agent` — alternative spatial substrate for agent-based
  models; bridged via `SpatialBridge`.
- `ksl.utilities.random` — velocity is a `GetValueIfc`; everything
  stochastic (`ConstantRV`, `TriangularRV`, …) plugs in directly.

### One picture

```
  Spatial model       ┌───────────────────────────────────────────┐
  (pick one)          │ DistancesModel  Euclidean2DPlane          │
                      │ RectangularGridSpatialModel2D             │
                      │ GreatCircleBasedSpatialModel              │
                      └────────────────┬──────────────────────────┘
                                       │ assign to a ProcessModel:
                                       │     spatialModel = ...
                                       ▼
  Trackable elements ─►  Entity.currentLocation     MovableResource(WithQ)
                          ▲                          ▲
                          │                          │
  Suspending verbs  ──────┴────  moveTo(loc)  move(forklift, toLoc)
  (live in ksl.modeling.entity)
```

---

## 2. The mental model

### A `SpatialModel` defines distance, not motion

`SpatialModel.distance(a, b)` answers *"how far is A from B?"*. The
entity package answers *"how long does it take?"* — it's
`distance / velocity`, scheduled as a delay event. Velocity comes from
the entity (or the movable resource) doing the moving; you can override
it per call.

### Locations are spatial-model-specific

A `Euclidean2DPlane.Point(3.0, 5.0)` is not interchangeable with a
`DistancesModel.Location("A")`. Cross-model use throws. Pick one
spatial model per `ProcessModel` and let it cascade to descendants.

### A model element inherits its parent's spatial model

By default, every `ModelElement` looks up its parent's
`spatialModel`. Assign `spatialModel = …` in your `ProcessModel`'s
`init` to override; the change cascades to children. Do this *before*
declaring entities and resources that depend on it — once a
`SpatialElement` is constructed, its spatial model is fixed.

### Entities and movable resources track position

Both implement `SpatialElementIfc`. Read/write `currentLocation`; the
entity's `moveTo(loc)` and the entity-side `move(forklift, toLoc)`
verbs update the position over simulated time. `SpatialElement` is a
lightweight tracker if you need to attach a position to something that
isn't an entity or a resource (a beacon, an inventory bin, a status
board).

### Velocity is a `GetValueIfc`

Use a `ConstantRV(2.0)` for fixed speed, a `RandomVariable` wrapping a
distribution for stochastic speed, or any computed value. Each call
resolves `velocity.value` once at the start of the motion — the moving
element doesn't accelerate or decelerate mid-leg.

---

## 3. Quick start

A two-station tandem queue where customers walk between stations on a
`DistancesModel`. Distances are in feet, velocity in feet-per-minute,
service times in minutes:

```kotlin
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV

class WalkingTandem(parent: ModelElement, name: String? = null) :
    ProcessModel(parent, name) {

    private val dm = DistancesModel()
    private val entrance = dm.Location("Entrance")
    private val station1 = dm.Location("Station1")
    private val station2 = dm.Location("Station2")
    private val exit = dm.Location("Exit")

    init {
        dm.addDistance(entrance, station1, 60.0, symmetric = true)
        dm.addDistance(station1, station2, 30.0, symmetric = true)
        dm.addDistance(station2, exit, 60.0, symmetric = true)
        dm.defaultVelocity = TriangularRV(88.0, 176.0, 264.0)  // feet / min
        spatialModel = dm
    }

    private val worker1 = ResourceWithQ(this, "worker1")
    private val worker2 = ResourceWithQ(this, "worker2")
    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))

    private inner class Customer : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            currentLocation = entrance
            moveTo(station1);  use(worker1, delayDuration = st1)
            moveTo(station2);  use(worker2, delayDuration = st2)
            moveTo(exit)
        }
    }
    private val gen = EntityGenerator(::Customer, ExponentialRV(1.0, 1), ExponentialRV(1.0, 1))
}

fun main() {
    val m = Model()
    WalkingTandem(m, "WT")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20_000.0
    m.lengthOfReplicationWarmUp = 5_000.0
    m.simulate()
    m.print()
}
```

What this shows: declaring a `DistancesModel` and three locations,
wiring symmetric distances, binding the model into a `ProcessModel`'s
`init`, setting a customer's starting `currentLocation`, and using
`moveTo` between work steps. The customer's process body reads like
"walk in, do step 1, walk to step 2, do step 2, walk out."

This is the canonical pattern. Recipe 1 is the decision table for
which spatial model to use; the rest are variations.

---

## 4. How do I…?

### ...pick a spatial model — four options

| Use | When |
|---|---|
| `DistancesModel` | A graph of named locations with explicit pairwise distances — floorplans, route networks, anywhere you have a map with measured edges. |
| `Euclidean2DPlane` | Continuous `R²` with straight-line distance — open-floor warehouses, simulated terrain, anywhere coordinates and Euclidean distance make sense. |
| `RectangularGridSpatialModel2D` | A lattice — discrete cells with cell-based location queries. Good for grid-pattern factories or anywhere you want "which cell is at (col, row)?" |
| `GreatCircleBasedSpatialModel` | Lat/lon geographic distance with a circuity adjustment — long-haul logistics, fleet routing, anywhere real geography matters. |

Switching later is painful — locations are spatial-model-specific, so
plan on picking one per `ProcessModel`. The recipes below show each in
turn.

### ...use a `DistancesModel`

A graph of named locations with explicit distances. Symmetric routes
are the common case; asymmetric ones (one-way streets) require either
two `addDistance` calls or `symmetric = false`. Bulk loading from
`DistanceData` is supported for JSON round-trip.

```kotlin
class RouteNetworkModel(parent: ModelElement) : ProcessModel(parent) {
    private val dm = DistancesModel()
    val a = dm.Location("A")
    val b = dm.Location("B")
    val c = dm.Location("C")

    init {
        // Symmetric routes — common case.
        dm.addDistance(a, b, distance = 12.0, symmetric = true)
        dm.addDistance(b, c, distance = 8.0, symmetric = true)
        // Asymmetric route — only declare the direction(s) you need.
        dm.addDistance(a, c, distance = 25.0)

        // Or in bulk from data:
        dm.addDistances(
            listOf(
                DistanceData(fromLoc = "A", toLoc = "C", distance = 25.0),
                DistanceData(fromLoc = "C", toLoc = "A", distance = 30.0),
            )
        )

        dm.defaultVelocity = ConstantRV(5.0)
        spatialModel = dm
    }
}
```

`DistancesModel` distances are *absolute* per pair — the model does
not compose `a → b → c` to derive `a → c`. Declare every pair you'll
ever query, or trust your code never to ask for an undeclared pair.

### ...use a Euclidean 2D plane

Continuous `(x, y)` with straight-line distance. Construct locations
with `plane.Point(x, y, "name")`:

```kotlin
class EuclideanModel(parent: ModelElement) : ProcessModel(parent) {
    private val plane = Euclidean2DPlane()
    val origin = plane.Point(0.0, 0.0, "Origin")
    val depot = plane.Point(10.0, 5.0, "Depot")
    val store = plane.Point(20.0, 15.0, "Store")

    init {
        plane.defaultVelocity = ConstantRV(2.0)
        spatialModel = plane
    }
}
```

The plane has unbounded extent — there's no enclosing rectangle. Use
`RectangularGridSpatialModel2D` instead when you need bounded cells
or grid neighborhood queries.

### ...use a rectangular grid

A lattice with rows × columns over a `(width, height)` rectangle.
Locations are `GridPoint(x, y, name)` — the grid will project them to
the containing cell:

```kotlin
class GridModel(parent: ModelElement) : ProcessModel(parent) {
    private val grid = RectangularGridSpatialModel2D(
        width = 100.0,
        height = 100.0,
        numRows = 10,
        numCols = 10,
    )
    val nw = grid.GridPoint(0.0, 0.0, "NW")
    val center = grid.GridPoint(50.0, 50.0, "Center")
    val se = grid.GridPoint(100.0, 100.0, "SE")

    init {
        grid.defaultVelocity = ConstantRV(1.0)
        spatialModel = grid
    }
}
```

The origin is the upper-left corner; the y-axis grows downward (the
standard 2D screen convention). Distance is Euclidean across cells.

### ...use lat/lon coordinates

For real-world geography. `GPSCoordinate(lat, lon)` gives a location;
`circuityFactor` adjusts the great-circle distance to reflect that
real road / rail networks are longer than the geometric shortest path:

```kotlin
class GeoModel(parent: ModelElement) : ProcessModel(parent) {
    private val world = GreatCircleBasedSpatialModel()
    val ny = world.GPSCoordinate(latitude = 40.7128, longitude = -74.0060, aName = "NewYork")
    val la = world.GPSCoordinate(latitude = 34.0522, longitude = -118.2437, aName = "LosAngeles")

    init {
        world.circuityFactor = 1.2          // road network ~20% longer than great-circle
        world.defaultVelocity = ConstantRV(800.0)   // km / h, cruise speed
        spatialModel = world
    }
}
```

`earthRadius` is configurable too if you need a non-Earth body (the
defaults are Earth in km).

### ...bind a spatial model to a process model

Assign `spatialModel = …` inside the `ProcessModel`'s `init` *before*
declaring resources and entities. The override cascades to all
descendants:

```kotlin
class BindingPattern(parent: ModelElement) : ProcessModel(parent) {
    private val dm = DistancesModel()
    val home = dm.Location("Home")

    init {
        dm.addDistance(home, home, distance = 0.0)
        spatialModel = dm                      // bind first
    }

    // Now anything constructed below sees the DistancesModel.
    private val server = ResourceWithQ(this, "Server")
}
```

A `SpatialElement` (and anything that contains one — `Entity`,
`MovableResource`) resolves its spatial model at construction. Late
re-binding does not migrate already-constructed elements; build the
binding first.

### ...move an entity with `moveTo`

`moveTo(location)` lives on `KSLProcessBuilder` (in the entity
package). It uses the entity's default velocity unless you override
it. Initialize `currentLocation` before the first move:

```kotlin
class MoveToDemo(parent: ModelElement) : ProcessModel(parent) {
    private val dm = DistancesModel()
    private val a = dm.Location("A")
    private val b = dm.Location("B")

    init {
        dm.addDistance(a, b, distance = 100.0, symmetric = true)
        dm.defaultVelocity = ConstantRV(10.0)
        spatialModel = dm
    }

    private inner class Walker : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            currentLocation = a                // initialize position
            moveTo(b)                          // 100 / 10 = 10 time units
            moveTo(a)                          // return
        }
    }
    private val gen = EntityGenerator(::Walker, ExponentialRV(5.0, 1), ExponentialRV(5.0, 1))
}
```

`moveTo(b)` resolves the entity's velocity, computes `distance(a, b) /
velocity`, schedules a delay event, then updates `currentLocation` to
`b` when the delay fires. From the simulation's perspective it's a
single suspension; from the script's perspective it's one line.

### ...override per-move velocity

Pass `velocity` to `moveTo` for a stochastic or context-specific
speed — useful when the entity moves at different speeds on different
legs (loaded vs. empty, careful vs. urgent):

```kotlin
class VariableVelocity(parent: ModelElement) : ProcessModel(parent) {
    private val dm = DistancesModel()
    private val a = dm.Location("A")
    private val b = dm.Location("B")
    private val slow = RandomVariable(this, TriangularRV(0.5, 1.0, 1.5))
    private val fast = ConstantRV(5.0)

    init {
        dm.addDistance(a, b, distance = 50.0, symmetric = true)
        dm.defaultVelocity = ConstantRV(2.0)
        spatialModel = dm
    }

    private inner class Mover : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            currentLocation = a
            moveTo(b, velocity = slow.value)   // stochastic per move
            moveTo(a, velocity = fast.value)   // fast on the return leg
        }
    }
    private val gen = EntityGenerator(::Mover, ExponentialRV(5.0, 1), ExponentialRV(5.0, 1))
}
```

`velocity` resolves once at the start of the leg. The mover doesn't
accelerate; if you need acceleration, break the leg into segments and
override each.

### ...use a `MovableResourceWithQ`

A `MovableResourceWithQ` is a seizable, queueable resource that has a
position and can move. From the process body it looks like any other
resource (`seize` / `release`); the difference is that you also call
`move(forklift, toLoc)` between seize and release to relocate it:

```kotlin
class MovableResourceDemo(parent: ModelElement) : ProcessModel(parent) {
    private val dm = DistancesModel()
    private val dock = dm.Location("Dock")
    private val machine = dm.Location("Machine")

    init {
        dm.addDistance(dock, machine, distance = 30.0, symmetric = true)
        dm.defaultVelocity = ConstantRV(5.0)
        spatialModel = dm
    }

    private val forklift = MovableResourceWithQ(
        parent = this,
        initLocation = dock,
        defaultVelocity = ConstantRV(5.0),
        name = "Forklift",
    )

    private inner class Job : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(forklift)            // wait for the forklift
            move(forklift, toLoc = dock)       // empty travel to pickup
            move(forklift, toLoc = machine)    // loaded travel to drop-off
            release(a)
        }
    }
    private val gen = EntityGenerator(::Job, ExponentialRV(15.0, 1), ExponentialRV(15.0, 1))
}
```

`MovableResource` is single-unit by design — capacity is 0 or 1. For
multiple forklifts of the same kind, use a pool. See `ksl-entity.md` §4
"...move an entity in space" for the same recipe seen from the
entity-package side.

### ...pool movable resources (`MovableResourcePoolWithQ`)

A pool of `MovableResource`s sharing one queue. The default selection
rule picks the closest available resource to the request location:

```kotlin
class MovableResourcePoolDemo(parent: ModelElement) : ProcessModel(parent) {
    private val dm = DistancesModel()
    private val zone1 = dm.Location("Zone1")
    private val zone2 = dm.Location("Zone2")

    init {
        dm.addDistance(zone1, zone2, distance = 40.0, symmetric = true)
        dm.defaultVelocity = ConstantRV(3.0)
        spatialModel = dm
    }

    private val r1 = MovableResource(this, zone1, ConstantRV(3.0), "F1")
    private val r2 = MovableResource(this, zone1, ConstantRV(3.0), "F2")
    private val r3 = MovableResource(this, zone2, ConstantRV(3.0), "F3")

    private val pool = MovableResourcePoolWithQ(
        parent = this,
        movableResources = listOf(r1, r2, r3),
        defaultVelocity = ConstantRV(3.0),
        name = "Forklifts",
    )

    private inner class Job(val pickup: LocationIfc, val drop: LocationIfc) : Entity() {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(pool, requestLocation = pickup)
            release(a)
        }
    }
}
```

`requestLocation = pickup` tells the closest-rule which resource to
pick. Override the rule by passing a `MovableResourceSelectionRule`
to `seize` for custom strategies (cheapest, latest-idle, etc.).

### ...track a custom `SpatialElement`

When the position-bearing thing isn't an entity or a resource — a
beacon, a sensor, a billboard — wrap it as a `SpatialElement` and
update its `currentLocation` directly. Updates from outside a process
body are instantaneous:

```kotlin
class CustomSpatialElement(parent: ModelElement) : ProcessModel(parent) {
    private val plane = Euclidean2DPlane()
    private val origin = plane.Point(0.0, 0.0, "Origin")

    init { spatialModel = plane }

    val beacon: SpatialElement = SpatialElement(this, initLocation = origin, aName = "Beacon")

    fun moveBeaconTo(p: LocationIfc) {
        beacon.currentLocation = p             // instant update; not a suspending move
    }
}
```

`SpatialElement` is observable — attach an `Observer<SpatialElementIfc>`
to react to position changes from elsewhere in the model.

### ...read distances and locations programmatically

Outside a `process { … }` body, you can still query the spatial model
for diagnostics, routing logic, or analytic checks:

```kotlin
fun computeDistances() {
    val m = Model()
    val mdl = MoveToDemo(m)
    val sm: SpatialModel = mdl.spatialModel
    val dm = mdl.spatialModel as DistancesModel    // when you know the type
    val locA = dm.locations.first()
    val locB = dm.locations.last()
    val d: Double = sm.distance(locA, locB)
    val d2: Double = locA.distanceTo(locB)         // equivalent
}
```

`spatialModel.distance(a, b)` and `location.distanceTo(other)` are
equivalent — pick whichever reads better at the call site. Both
throw on cross-model use.

### ...read movement statistics

`MovableResource` (and the `WithQ` variant) tracks three fractions in
addition to the standard `Resource` stats — what fraction of time it
spent moving in total, loaded, and empty:

```kotlin
fun readMovementStats(forklift: MovableResourceWithQ) {
    val moving   = forklift.fracTimeMoving.acrossReplicationStatistic.average
    val carrying = forklift.fracTimeTransporting.acrossReplicationStatistic.average
    val empty    = forklift.fracTimeMovingEmpty.acrossReplicationStatistic.average
    val busy     = forklift.numBusyUnits.acrossReplicationStatistic.average
    val waitTime = forklift.waitingQ.timeInQ.acrossReplicationStatistic.average
}
```

`fracTimeTransporting` is the productive movement (carrying load);
`fracTimeMovingEmpty` is the cost of deadheading. Together they're a
quick utilization-of-motion diagnostic.

---

## 5. The key types at a glance

A compact tour. Member-level detail is on the Dokka pages.

**Spatial models**

- `SpatialModel` — abstract base; defines `distance`, `defaultLocation`,
  `defaultVelocity`.
- `DistancesModel` — graph of named locations + explicit pairwise
  distances. JSON round-trip via `DistanceData` / `DistancesData`.
- `Euclidean2DPlane` — continuous `R²` with Euclidean distance.
- `RectangularGridSpatialModel2D` — lattice with cell-based queries.
- `GreatCircleBasedSpatialModel` — lat/lon with great-circle distance
  + configurable `earthRadius` and `circuityFactor`.

**Locations**

- `LocationIfc` — the trait; every location has an `id`, a `name`, and
  a `distanceTo(other)` method.
- Concrete types are inner classes of each spatial model:
  `DistancesModel.Location`, `Euclidean2DPlane.Point`,
  `RectangularGridSpatialModel2D.GridPoint`,
  `GreatCircleBasedSpatialModel.GPSCoordinate`.

**Spatial elements**

- `SpatialElementIfc` — the trait for things-with-a-position;
  observable.
- `SpatialElement` — the standalone implementation for non-entity,
  non-resource cases.
- `SpatialModelElement` — the abstract base for `ProcessModel`s that
  *are themselves* spatial elements (e.g., a workstation with a fixed
  position).

**Velocity**

- `VelocityIfc` — the trait; every velocity-bearing object exposes
  `velocity: GetValueIfc`.

**Movable resources**

- `MovableResource` — single-unit movable, seizable resource.
- `MovableResourceWithQ` — same, with a bundled request queue.
- `MovableResourcePool` — set of movable resources with selection /
  allocation rules.
- `MovableResourcePoolWithQ` — pool + bundled queue.
- `MovableResourceRules` — shipped selection / allocation strategies
  (closest-first by default).

**Serialization**

- `DistanceData` — `@Serializable` directed-distance datum.
- `DistancesData` — alias for `List<DistanceData>` (JSON round-trip
  shape for a `DistancesModel`).

---

## 6. Gotchas & best practices

- **Pick the spatial model that matches your distance metric.**
  `DistancesModel` for "route network with measured edges,"
  `Euclidean2DPlane` for "straight-line distance on (x,y),"
  `RectangularGridSpatialModel2D` for "I want lattice / cell
  semantics," `GreatCircleBasedSpatialModel` for "real geography."
  Switching later requires renaming locations and re-asserting
  distances.
- **Cross-model location use throws.** A `Euclidean2DPlane.Point` is
  invalid in a `DistancesModel.distance(...)` call. Validate at the
  authoring boundary; the runtime catches it but the error reads as a
  precondition failure.
- **Bind the spatial model before declaring entities/resources.**
  Assign `spatialModel = ...` *first* in your `ProcessModel`'s `init`.
  Once a `SpatialElement` resolves its spatial model at construction,
  late re-binding doesn't migrate it.
- **`moveTo` uses the entity's velocity by default.** Override per-leg
  with `moveTo(loc, velocity = ...)` when speed varies (loaded vs.
  empty, urgent vs. routine).
- **`MovableResource` is single-unit by design.** Capacity is 0 or 1.
  Use a pool for multiple resources of the same kind.
- **Set `currentLocation` before the first `moveTo`.** A freshly
  created entity has no spatial location until you assign one;
  `moveTo` from an unset location is ambiguous.
- **`addDistance(..., symmetric = true)` is your friend** for routes
  that are the same in both directions. The default is *asymmetric*
  (`symmetric = false`); declaring one direction does not implicitly
  declare the other.
- **Distances are absolute, not edge lists.** `DistancesModel` does
  *not* compose `a → b → c` to derive `a → c`. Declare every pair
  you'll ever query.
- **Velocity is sampled once per move.** No acceleration model. For
  variable in-leg speed (slow then fast), split the move into legs.

---

## 7. See also

**Related KSL packages**

- `ksl.modeling.entity` — the process view that hosts `moveTo` and
  `move` (the suspending verbs that drive everything in this guide).
  Movable resources live here from the user's perspective even though
  their types are declared here.
- `ksl.modeling.station` — the queueing-network view; transparently
  uses `MovableResourceWithQ` for movable-server stations.
- `ksl.modeling.agent` — a parallel spatial system aimed at
  agent-based models (statechart-reactive, force dynamics, 3D
  variants). Bridged via `SpatialBridge` when both views need to
  share coordinates.
- `ksl.utilities.random` — velocity is a `GetValueIfc`; constants,
  random variables, and computed values all plug in directly.

**Other usage guides** *(in `docs/guides/`)*

- `ksl-entity.md` — the process-view package this guide most directly
  serves.
- `ksl-station.md` — uses these resources for movable-server stations.
- `ksl-agent.md` — the parallel spatial system in agent-based models.
- `ksl-modeling.md` — the underlying modeling primitives.
- `ksl-utilities-random.md` — velocity distributions, stream control.

**Examples** *(under `KSLExamples/.../`)*

| Where | Example | Showcases |
|---|---|---|
| `book/chapter8/` | `TandemQueueWithUnconstrainedMovement` | the canonical `DistancesModel` + walking customers |
| `book/chapter8/` | `TandemQueueWithConstrainedMovement` (V1–V3) | movable workers carrying customers between stations |
| `book/chapter8/` | `TestAndRepairShopWithMovableResources` | a richer `MovableResourcePool` example |
| `book/chapter8/` | `StemFairMixerEnhancedWithMovement` | movement layered on a class-aware model |
| `book/chapter8/` | `TandemQueueWithConveyors*` | conveyor + spatial integration |
| `general/spatial/` | `TestSpatialModels` | the four spatial models compared side by side |
| `general/spatial/` | `TransporterExample` | a transporter pattern (pool + spatial queue) |

**KSL Book**

For background on replications, warmup, output analysis, and variance
reduction, see [the KSL Book](https://rossetti.github.io/KSLBook/) —
Chapter 8 covers the movement and material-handling material this
guide accompanies.
