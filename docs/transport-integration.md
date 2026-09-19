# Integrating the transport work into KSL

Notes for whoever lands the guided-path, fleet and AGV work in the live KSL repository. This
branch (`claude/guided-path-transporters-plan-ysyjyx`) was developed in a scratch repository, so
the point of this page is to say which parts are a copy and which parts are a decision.

## What the change is

135 commits, 240 files, **65,361 insertions against 30 deletions**. That ratio is the single most
useful fact about it: almost nothing existing was rewritten.

The work lives in three new packages under `KSLCore/src/main/kotlin/ksl/modeling/`:

| Package | Files | What it is |
|---|---|---|
| `guidedpath` | 33 | Zone-based guide paths: networks, links, zones, contention, crossings |
| `fleet` | 30 | Free-path fleets: dispatcher, tasks, tours, assignment policies |
| `agv` | 3 | A thin adapter binding a fleet vehicle to a guide-path body |

Tests: 118 new files, **576 tests** in those three packages (431 guidedpath, 145 fleet), plus four
`slow`-tagged classes that reproduce published shops against reference results
(`TestAndRepairCrossCheckTest`, `PaintingFlowLineCrossCheckTest`, `MetamorphicRelationTest`,
`QueueingLimitsTest`).

Four guides are added: `ksl-transport.md`, `ksl-transport-tutorial.md`, `ksl-guidedpath.md`,
`ksl-fleet.md`. The tutorial is held to its sources by `TransportTutorialCodeTest`, which fails if
a quoted line drifts from the file it claims to quote.

## What is not a copy

Ten existing `KSLCore/src/main` files are modified. These are the whole of the integration risk
and should be applied one at a time, not as a bulk patch.

**1. `ProcessModel.Entity` gains two interfaces — this is a decision, not a copy.**

```kotlin
open inner class Entity(aName: String? = null) : QObject(aName),
    SpatialElementIfc by SpatialElement(this@ProcessModel), VelocityIfc,
    ZoneHolderIfc, ZoneHolderRecordIfc
```

`Entity` is the base class of essentially every process-oriented KSL model, so this puts
guide-path zone-holding members on every entity in every model whether or not it has anything to
do with transport. It is one line and it is additive, but it is **not** removable by dropping the
three new packages, and that makes it the one part of this work that is not experimental-shaped.
Decide it deliberately: accept the widening now, or move the capability behind a delegate or an
extension so that only models that want it carry it.

**2. `AgentResource` shift handling moved into a new `ShiftControl`.** The public `goOffShift()`
and `goOnShift()` keep their signatures and delegate; the private `offShift` flag is gone. This is
the only behaviour-relevant refactor of existing code. It is covered by the existing suite.

**3. `MovableResource` now implements `VehicleMovementIfc`** and gained `@JvmOverloads` on its
constructor. Source-compatible for Kotlin; `@JvmOverloads` changes the generated Java surface by
adding overloads, which matters only to Java callers.

**4. Five new files land in existing packages** rather than the new ones, because they are
vocabulary the existing packages need: `entity/ShiftControl.kt`, and `spatial/FleetSpaceIfc.kt`,
`InterpolatedMovement.kt`, `MovePurpose.kt`, `VehicleMovementIfc.kt`.

**5. Additive-only, no deletions:** `KSLProcess.kt` (+788), `AnimationEvent.kt` (+152),
`ContinuousProjection.kt`, `SpatialBridge.kt`, `Euclidean2DPlane.kt`, `SpatialModel.kt`. These are
new suspending verbs and new members; nothing existing changed.

`KSLCore/build.gradle.kts` also gains a `fastTest` task that excludes the `slow` tag. Accept or
drop it, but if you drop it, note that the full `test` task then includes the validation runs.

## Suggested order

The base here is a snapshot of KSL from **24 August 2026**. Live KSL has moved since, and none of
that movement is visible from inside this repository — so assume the ten touchpoints above are
where conflicts will be.

1. **Copy the three new packages and their tests first**, plus the five new files in existing
   packages. Do not touch the ten modified files yet. This will not compile, and that is expected:
   the compiler errors are a precise list of what the touchpoints have to provide.
2. **Apply the six additive touchpoints** (`KSLProcess`, `AnimationEvent`, `ContinuousProjection`,
   `SpatialBridge`, `Euclidean2DPlane`, `SpatialModel`). Re-diff each against live KSL rather than
   overwriting: these files have almost certainly changed since August.
3. **Take the `Entity` decision** before applying it. Everything else can be reversed by deleting a
   directory; this cannot.
4. **Apply `AgentResource` / `ShiftControl` and `MovableResource` last**, and run the existing
   agent and spatial tests immediately after each.
5. **Then the examples, then the guides.** `KSLExamples` and the four guides are independent of the
   library and can land separately, or not at all, without affecting KSLCore.

## Verifying it

- `:KSLCore:fastTest` while working; `:KSLCore:test` at each milestone, because the four validation
  classes are the ones that would catch a movement-engine or space-layer regression.
- The twelve example run tasks in `KSLExamples/build.gradle.kts` (`simpleAgvExample`,
  `freePathFleetExample`, `retaskingExample`, …) are the end-to-end check. Capture their output
  before and after each step and diff it; the examples are deterministic, so any difference is real.
- `TransportTutorialCodeTest` will fail if the guides drift from the code during integration. That
  is a feature — it means the documentation cannot silently rot while the API is being adjusted.

Note that the normalisation script and baseline captures used during development were kept outside
the repository and **do not travel with a clone**. The example tasks do travel; re-baseline against
your own build, which is the right thing to do anyway.

## Known to be unfinished

- `ksl.modeling.agv` has no test class of its own. It is a thin adapter exercised through the
  guidedpath and fleet suites and the examples, but it is the one package with no direct coverage.
- `Tour.insert` and `Tour.remove` have no production caller. They are `internal`, tested, and
  deliberately ahead of the multi-load tour policy that will use them.
- `Executive.numEventsExecuted` is readable from inside the element tree (`ModelElement.executive`
  is `protected`) but not from outside it. No change was made; a model that wants to report it can
  expose it in one line.
