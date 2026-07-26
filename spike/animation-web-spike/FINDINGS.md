# Phase S spike — findings

_2026-07-26. Branch `animation-web`. Companion to `.claude/plans/animation-web-plan-2026-07-26.md`._

**Verdict: proceed to Phase 1 on Kotlin/JS (decision S3 holds). No plan-level design change needed —
but Phase 1's scope needs four corrections, recorded below and folded into the plan as rev 4.**

The spike loaded two real `.atf` traces in a browser and replayed them through the **real**
`ReplayModel`, `StepTimeline`, `PositionInterpolator` and `PlaybackController` compiled to Kotlin/JS.
21 KSL source files (3,406 lines) were copied in **verbatim** and compiled unmodified except for the
edits the plan already specifies. Spike-authored code: 3,993 lines including this note.

---

## 1. The six gate questions

| # | Question | Answer |
|---|---|---|
| 1 | Kotlin/JS + webpack toolchain viability | ✅ Works. Cold `compileKotlinJs` 24s; webpack 28s cold / 4–11s incremental. Node + Yarn auto-provisioned by the Gradle plugin (needs network on first build) |
| 2 | `DecompressionStream` gzip decode in-browser | ✅ **246 KiB wire → 2.55 MiB in 64 ms.** Static `.atf.gz` delivery is a non-issue |
| 3 | **G10** — `Long`→BigInt cost in `Map<Long, …>` replay paths | ✅ **Not a bottleneck.** 16,000 `Long`-keyed entity lookups in 76 ms = **0.005 ms/lookup**. **Resolves D7: no action, do not pre-optimise** |
| 4 | Bundle size | ✅ **456 KiB minified, 117 KiB gzipped** (dev bundle 4.1 MiB). Fine for book-figure embedding. Exceeds webpack's 244 KiB advisory, which is cosmetic here |
| 5 | Do `.atf` + `.lay.json` carry what a renderer needs? | ✅ Yes. Rendered queues with *identified* members, resource state colours + unit occupants, entity glyphs, 80 agents, continuous-space backdrop, clock, legend — from the two files alone. The layout-free path also works (spaces derived from `SpaceDefined`), with one caveat — see finding G |
| 6 | Natural shape of the `DrawCmd` vocabulary | ✅ Five primitives covered everything: `Polyline`, `Circle`, `Rect`, `Glyph`, `Text`. See §3 |

### Measured performance

| | AnnotatedClinic (797 events, 102 KB) | FlockVecOn (20,435 events, 2.6 MB) |
|---|---|---|
| fetch | 39 ms | 45–55 ms |
| **parse (JSON Lines)** | **192 ms** | **1,304–1,504 ms** |
| `ReplayModel.build` | 12 ms | 302–359 ms |
| scene build + draw | 1.8 ms/frame (10 cmds) | 2.9 ms/frame (84 cmds) |
| query storm | 0.01 ms per sample time | 0.38 ms per sample time |

Frame cost of ~3 ms for 84 draw commands leaves ~5× headroom at 60 fps. **Canvas 2D is more than
sufficient for the Phase 2 scope** — the PixiJS backend (Phase 6) stays demand-driven, not needed.

---

## 2. Corrections to the plan

### A. Parse CPU — not bandwidth — is the bottleneck  ⚠️ new

The plan framed trace size purely as a **bandwidth** story ("8 KB–250 KB gzipped, trivially within web
budgets"). That is true and remains true — but it is the wrong axis. The real cost is **CPU parse time**:
1.3–1.5 s of the ~1.9 s total load for the worst-case trace, i.e. **~75% of load time**, versus 64 ms to
decompress and 45 ms to fetch.

`AnimationEvent.decodeFromLine` runs kotlinx-serialization once per line: ~13,600–15,700 events/s on JS.
Nothing is wrong with it; there is simply a lot of it.

**Phase 2 must budget a mitigation.** Options, cheapest first: parse in a **Web Worker** so the main
thread stays responsive and the transport bar can show progress; **stream** the parse and start playing
from the first replication marker; or **lazily** index by event class. A 1.9 s blocking load with no
feedback is the difference between "instant" and "broken" for a book figure.

### B. `GridGeometrySpec` is **not** self-contained — G2 was wrong  ⚠️ correction

The plan asserted (G2, D3) that `GridGeometrySpec.kt` "imports only `kotlinx.serialization`", so one
`git mv` with the package preserved would resolve it with zero API break. **That was derived from an
import grep and is false.** Kotlin same-package references need no import, and the file reaches:

- `Cell` — declared in `Cell.kt` (100 lines; pure, imports only `kotlin.math` + serialization) ✅ shareable
- `MovementRule` — declared **inside `GridGraph.kt` at line 34**, a 499-line file ⚠️
- `GridGraph` — the 499-line algorithmic class, via the `toGridGraph()` extension ⚠️

Compiling it produced **14 errors**. The fix is still bounded but is three edits, not one:
1. Split `MovementRule` out of `GridGraph.kt` into its own file.
2. Move `toGridGraph()` to a KSLCore-side same-package extension file (the `AnimationLayoutFiles.kt` trick).
3. Share `GridGeometrySpec` + `CellCost` + `Cell` + `MovementRule`.

**Consequence: Phase 1 does touch `ksl.modeling.agent`**, which the plan said it would not.

### C. The same pattern again — pure DTOs embedded in model-coupled files  ⚠️ new

`ReplayModel` imports `ksl.animation.ConveyorInfo`, which lives in **`AnimationInventory.kt`** — a file
importing `Model`, `ModelElement`, `Conveyor`, `Resource`, `Queue`, `AgentModel`, the station types, and
**`kotlin.reflect.full.declaredMemberProperties` (JVM-only reflection)**. `ConveyorInfo` and
`SegmentInfo` are themselves pure `@Serializable` DTOs.

So B is not a one-off. **The format/model split in `ksl.animation` runs *within* files, not between
them.** Phase 1 therefore needs a **DTO-extraction pass**, not the file-move pass the plan assumed:

| File | Pure part to share | Model-coupled part to leave |
|---|---|---|
| `AnimationLayout.kt` | the DTOs | 5 file-I/O members (already planned) |
| `GridGeometrySpec.kt` | `GridGeometrySpec`, `CellCost` | `toGridGraph()` |
| `AnimationInventory.kt` | `ConveyorInfo`, `SegmentInfo` (+ likely `SpaceInfo` et al.) | the reflective model-graph walker |
| `GridGraph.kt` | `MovementRule` | `GridGraph` |

### D. The JVM-coupling audit undercounted — import-grep is not a dependency check  ⚠️ correction

The plan's headline de-risking fact was "JVM API usage in the replay package is `Rectangle2D.Double` ×11
+ `Path` ×2, **nothing else**". The first compile produced **217 errors**. The audit missed two whole
classes of coupling, both invisible to a `java\.` grep:

- **JVM-only Kotlin stdlib extensions:** `removeIf` (`ReplayModel:740`), `toSortedMap`, `toSortedSet`,
  `putIfAbsent`. These are `kotlin.collections` members that exist only in the JVM stdlib.
- **Same-package type references** needing no import (finding B).

The *shape* of the plan survives — every failure was mechanical, and the fixes are one-liners
(`removeIf` → `removeAll`). But "nothing else" was overconfident, and the same undercount likely applies
anywhere else in the repo where portability was assessed by import grep.

### E. A third of the replay layer does not need sharing at all  ✅ scope reduction

`AutoLayout.kt` (287), `AutoLayoutBuilder.kt` (195) and `TraceAccumulators.kt` (288) — **770 lines and
141 of the 217 errors (65%)** — are **authoring-side**, not player-side. They depend on `Model`,
`AnimationInventory` and `SpaceInfo`, and are used only by `AnimationAppController` and `ReplayPanel` to
scaffold a layout. `ReplayModel` does not reference them at all.

They stay in KSLApp as JVM-only. This offsets the scope increase from B and C.

**Validated shareable set for a player** (compiles clean on Kotlin/JS): `AnimationEvent`,
`AnimationTraceHeader`, `AnimationLayout`, `CaptureSpec`, `OverlaySpec`, `AnimationSink`,
`AnchorResolver`, `LayoutGeometry`, `ObjectClassSeeding`, `PositionInterpolator`, `ReplayCompatibility`,
`ReplayModel`, `StepTimeline`, `StreamingTraceMiner`, `PlaybackController`, `AnimationSource`, plus the
extracted DTOs and the new `BoundingBox`.

---

## 3. Design results

### F. `worldBounds()` belongs in the shared builder — evidence for S6/D5  ✅

The naive world-bounds rule (union the declared layout rect with the motion bounds) **crammed 80 boids
into 3% of the viewport**: a 100-unit continuous space unioned with the default 1000×700 canvas fits the
1000×700 box. A trace with no layout is the worst case, since the declared rect is then pure default.

Both existing renderers already solve this independently — `AnimationLayoutRenderer.worldBounds()` has an
explicit "content tiny relative to canvas ⇒ fit content" heuristic, and `SimulationCanvas` has its own
variant. **A third renderer re-derived it and got it wrong.** That is direct, concrete evidence for
putting `worldBounds()` in the shared `SceneBuilder` rather than per surface.

### G. Default glyph size is DES-calibrated and unusable for ABM traces without a layout  ⚠️ new

`ObjectClassDefinition.size` defaults to **10.0 world units**, sensible for a DES layout spanning
hundreds of units (AnnotatedClinic: 640×380) and ~6× too large for a spatial model spanning tens
(FlockVecOn's authored layout declares `Boid` size **1.8** in a 100-unit space). Rendering a trace
*without* a layout — the "self-describing, renders from the trace alone" path (NF6) — therefore produces
unusable output for every agent model.

`SceneBuilder` should derive a default glyph size from the world bounds (e.g. a small fraction of the
diagonal) when the layout declares no object class, instead of using the absolute default.

### H. The `DrawCmd` vocabulary  ✅

Five primitives sufficed for background, queues + members, resources + occupants, entity glyphs, agents,
spaces (continuous/grid/network), clock and legend: `Polyline`, `Circle`, `Rect`, `Glyph`, `Text`.

The one real vocabulary result: **`Glyph` should stay symbolic** rather than being desugared into
`Circle`/`Rect` at build time. Shape is per-object-class *layout* data, so keeping it symbolic lets the
surface pick the primitive, and lets an `imageRef` fall back to a shape without the builder needing to
know whether the image loaded.

The `DrawSpace.WORLD` / `DrawSpace.SCREEN` split earned its place immediately: the clock and legend must
not scale with zoom, which is exactly what `SimulationCanvas` achieves today by swapping transforms
mid-paint. Making it explicit in the scene is what lets a surface be a dumb executor.

---

## 4. Reproducing

```bash
./gradlew -p spike/animation-web-spike jsBrowserDevelopmentWebpack
mkdir -p spike/animation-web-spike/build/serve
cp spike/animation-web-spike/build/kotlin-webpack/js/developmentExecutable/spike.js spike/animation-web-spike/build/serve/
cp -r spike/animation-web-spike/src/jsMain/resources/* spike/animation-web-spike/build/serve/
(cd spike/animation-web-spike/build/serve && python3 -m http.server 8731)
```

Then open `http://localhost:8731/` and switch traces with the text field (`AnnotatedClinic`, `FlockVecOn`).

Trace fixtures under `src/jsMain/resources/traces/` were copied from
`KSLAppSwingAnimation/build/animations/`. Note `*.gz` is gitignored, so the `.atf.gz` used for the
DecompressionStream measurement must be regenerated with `gzip -c FlockVecOn.atf > FlockVecOn.atf.gz`.

## 5. Disposal

Delete `spike/` at the start of Phase 1, per the plan. Nothing here is intended to survive; its outputs
are this note and the plan's rev 4. The `BoundingBox` in `ksl/animation/geom/` and the `MiniScene.kt`
vocabulary are the two pieces worth carrying forward by hand.
