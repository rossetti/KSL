# Polishing an animation layout

How to take an auto-generated layout and make it worth showing. Written for two readers: someone doing
this by hand with the Swing app, and an agent driving the MCP server. The judgements are the same either
way — only the tool calls differ (see [Doing this over MCP](#doing-this-over-mcp)).

The premise that makes any of this affordable: **a trace and a layout are separate files bound by element
name.** Capture the trace once, then edit the layout freely — no recompiling, no re-running the model.
Every iteration below costs a render, not a simulation.

---

## The loop

1. **Capture** a trace and an auto-layout starting point.
2. **Render a contact sheet** — several frames spread across the run, not one. A layout that reads well at
   `t = 0` (everything idle, every queue empty) can be unreadable at steady state.
3. **Fix defects in the order below.** The order matters: it runs from the ones that change positions to
   the ones that decorate them, so later work is not invalidated by earlier work.
4. **Re-render and look again.** Stop when nothing in the frame is unexplained.

Start from the **auto-layout**, not from an example's `AnimationBuilder` layout. The auto-layout is what a
user actually gets — it is what the desktop app's *Auto Layout* button and the MCP `auto_layout` tool both
produce — so anything achieved from there is reproducible by them. It also mines the trace for real
positions, flow order, mover homes and observed extent, none of which a hand-written layout knows.

Keep the polish as a **script that transforms the starting layout**, not as a hand-edited JSON blob. The
script is where each change carries its reason, and it lets the layout be regenerated when the model's
geometry changes. `polish-Example13.py` is the worked example.

---

## When the model has to change

Most polishing needs no model change at all: a trace already carries positions, movement, queues, resource
states and responses. One thing is different. A bare `delay()` has **no geometry and no name**, so it
renders as nothing, and an animation storage binds to a delay's *suspension name* — meaning an unnamed
delay cannot be drawn even in principle. Naming it is a model edit:

```kotlin
delay(myInteractionTimeRV, suspensionName = "ConversationArea")
```

This is metadata. It names a suspension point and touches neither the random number streams nor the
schedule, so every statistic the model produces is unchanged. Prove that rather than asserting it: capture
a trace before and after and diff them with `suspensionName` stripped. If the two are identical, the naming
changed nothing.

**Do not instrument a book example.** The book's models teach modelling, and threading animation concerns
through them puts a second subject into code written to explain one. Copy the model into
`ksl.examples.general.animationbundle.models` and instrument the copy — the student processes are private,
so a subclass cannot override them and a copy is the only option. `AnimatedStemFairMixer` is the worked
example: a copy of chapter 8's `StemFairMixerEnhancedWithMovement` whose sole difference is that its two
bare delays are named.

Movement needs no instrumentation. A `moveTo` already reports the locations it runs between, so paths come
out of the trace on their own.

---

## The defect catalogue

In fix order. Each entry is *symptom → cause → fix*.

Several of these are now handled by the generator itself, marked **auto**. They are still described, because
they remain the things to check, and because a hand-written layout can reintroduce any of them.

### 1. The process reads in the wrong direction — *auto*

**Symptom.** The first step of the process is on the right, or the flow zig-zags.

**Cause.** Placement that is faithful to the model has no idea which way a reader's eye travels. Classical
MDS in particular fixes only the *shape*: a configuration is determined up to rotation, reflection and
translation, so which end lands on the left is arbitrary.

**Fix.** Re-orient the whole configuration. The generator does this now — `LocationFlow` mines the reading
order from how entities moved and `withReadableOrientation` turns the placement — so reach for this only when
a hand-written layout has gone in backwards, or when the generator declined (it refuses rather than half-turn
when the first and last of the process are not on the outside of the placement). Because a rigid transform preserves every pairwise distance,
this costs nothing — a placement derived from a distance matrix stays a faithful placement. Search all
360° and both reflections for the orientation that satisfies your reading constraints (first step
leftmost, last step rightmost) and then optimise within the survivors — flattest is usually right, since
for a fixed shape spreading horizontally *is* flattening vertically.

**Verify it.** Assert that pairwise distances survive the transform. A sign error in the rotation still
produces a plausible-looking picture, with the wrong distances in it.

**What this cannot fix.** A station the model itself places far from everything else. No rigid transform
brings it in. Either keep the placement faithful and let scale and routes carry the composition, or
abandon the distance-faithful placement and place by hand — but do not quietly distort the distances while
still calling them a distance matrix.

### 2. Stations do not read as "queue + resource" — *auto*

**Symptom.** Long vertical stalks hanging off each station; the eye travels down when the process travels
right.

**Cause.** A queue authored with `growthDegrees = 90.0`, so members stack below the head.

**Fix.** `growthDegrees = 180.0`, head just left of the resource, members growing further left. A row then
reads *members → head → server*, in the same direction as the process. This is what the auto-layout
already does; hand-written layouts are where it goes wrong.

### 3. Everything is too small for the canvas

**Symptom.** A mostly white frame with specks in it.

**Cause.** Element sizes carried over from a small hand-placed canvas, now spread over an arrangement whose
extent comes from real distances.

**Fix.** Size elements to the *arrangement*, not to the old canvas. A useful anchor: the widest block
should be about a fifth of the arrangement's width. That reads as a machine; a twentieth reads as a dot,
and a half reads as a wall.

Remember that **font sizes are world units too**, so chrome has to grow with the elements or the caption
ends up shouting over them. Anything sized as a fraction of the world extent scales correctly; anything
left at a constant does not.

### 4. A multi-server station's queue is tucked under its own block — *auto*

**Symptom.** The queue head disappears behind the leftmost server cell.

**Cause.** A resource is drawn as **one cell per unit of capacity**, in a row centred on its position, so
its half-width is `capacity × size / 2` — not `size / 2`. Assuming a single cell under-reserves the space.

**Fix.** Offset the queue head by the real half-width. Capacity is a property of the *run*, not of the
layout, so read it from the trace (`ResourceStateChanged` carries `capacity`).

### 5. Movers are parked on top of the machines — *auto*

**Symptom.** A worker glyph sits inside a server cell, hiding the entity being worked on.

**Cause.** A movable resource's drawn position resolves through the **location** of the same name. A
machine placed exactly on its location has workers land on it.

**Fix.** Offset the machine from its location. The location then reads as the spot on the floor where a
worker stands, and the machine sits beside it — both more legible and a truer picture. The location's own
open-square marker becomes meaningful: an empty parking spot, covered when a worker is there.

### 6. Stations float in white space — *auto*

**Symptom.** Correctly placed elements with nothing connecting them; large empty regions.

**Fix.** Draw the routes. The generator emits these now, from `EntityRoutes`: the station-to-station moves
that **entities** actually made (`MoveStarted` with `fromLocationName`/`toLocationName`), one `paths` entry
per distinct undirected pair. This costs no invention — every line is a move that happened — and it turns a scatter of
elements into a floor plan.

**Exclude the movers' own repositioning.** A worker will travel anywhere to fetch its next job, so
including `SpatialElementMoved` makes the route graph nearly complete and says nothing about how the system
is routed.

Empty space is also where chrome belongs. A read-out pinned to an edge adds to the emptiness; the same
read-out in the hole the elements left over fills it.

### 7. Four labels land on one point

**Symptom.** Overlapping text at every station.

**Cause.** A station typically attracts its own name, its resource's name, its queue's name and count, and
any mover parked on it — all at the same coordinates.

**Fix.** Hide the redundant ones *before* moving anything, so you are not solving collisions that should
not exist. Usually: hide the location name (it repeats the resource label), hide the queue name (same),
keep the queue's **count** (the only informative part), hide mover labels (they land on whatever the mover
is standing on). Retitle what remains to something a reader recognises — "Diagnostics", not
"DiagnosticWorkers".

Derive label offsets from **glyph size**, never from a constant: a 33-unit machine and a 25-unit worker
need different clearance, and one constant collides with one or the other.

### 8. Colours mean the wrong thing

**Symptom.** A mover camouflages against a station, or reads as a state change.

**Cause.** Red is the resource BUSY colour and green is IDLE. A red mover on a busy station disappears; a
green one looks like a state.

**Fix.** Pick mover hues the state palette does not use.

### 9. Internal elements clutter the picture — *auto*

**Symptom.** The longest line on screen belongs to something that is not part of the system being modelled.

**Fix.** Drop a resource pool's own queue (movers waiting to be assigned), and keep `maxShown` near the
length the queue actually reaches. A queue's extent line is `spacing × maxShown`, so a generous `maxShown`
advertises a capacity that never occurs and dominates the frame.

---

## Checking your work

- **Render several frames across the run**, including the last. Idle-green end states and busy mid-run
  states fail differently.
- **Compare the two renderers.** The desktop canvas and the web player are separate implementations of the
  same layout. Exporting the same layout to HTML and comparing at a matched time is a cheap parity check —
  it is how the bar-caption difference in `SceneBuilder` was found.
- **Nothing in the frame should be unexplained.** If you cannot say what a mark means, a reader cannot
  either.

---

## Doing this over MCP

The same loop, driven by tools. All four accept and return layout text, so the layout is edited as a
document between calls.

| Step | Tool | Notes |
|---|---|---|
| 1. Starting layout | `auto_layout` | Pass the run's `resultId` so the trace is mined for real positions, flow order and mover homes. Returns JSON or TOML. |
| 2. Edit | — | Apply the fixes above to the returned text. |
| 3. Check the edit | `validate_animation_layout` | Catches names that do not exist in the model. Run it before rendering; a typo'd element name silently draws nothing. |
| 4. Look at it | `render_animation_layout` | A **static placement preview** — positions and sizes, no entities in queues and no movers in transit. Good for steps 1–5 of the catalogue, useless for the rest. |
| 5. Look at it moving | `render_animation_html` | The live animation, self-contained. This is where steps 6–9 are actually judged. |
| 6. Keep it | `save_animation_layout` | Writes a `.lay.toml`/`.lay.json` the desktop app can open. |

Two things an agent should know:

- **Do not judge a layout from the static preview alone.** Queue lengths, mover positions and label
  collisions only exist during replay. The static preview will happily show a clean-looking layout whose
  every station is unreadable at steady state.
- **The trace is the source of facts.** Capacity, routes, which queues actually fill, how long the run is
  — read them from the trace rather than assuming. Several fixes above (4 and 6 in particular) are
  impossible without it.

---

## Reproducing the worked example

```bash
# 1. capture the trace, an auto-layout starting point, and the example's own layout
#    (re-running overwrites the trace but never a layout you have started polishing)
./gradlew :KSLExamples:showcaseCapture -PmodelName=Example13MovableResources -Pout=build/showcase
./gradlew :KSLExamples:showcaseCapture -PmodelName=list      # everything capturable

# 2. apply the polish
python3 docs/animations/polish-Example13.py

# 3. a contact sheet of frames across the run
./gradlew :KSLAppSwingAnimation:renderFrames \
  -Ptrace=build/showcase/Example13MovableResources.atf \
  -PlayoutFile=docs/animations/layouts/Example13MovableResources.lay.json \
  -Pframes=6 -Pout=build/showcase/sheet -Pw=1000 -Ph=850

# 4. the same layout in the browser, for the parity check
./gradlew :KSLApp:exportAnimationHtml \
  -Ptrace=build/showcase/Example13MovableResources.atf \
  -PlayoutFile=docs/animations/layouts/Example13MovableResources.lay.json \
  -Pout=build/showcase/Example13.html
```

Note `-PlayoutFile`, not `-Playout`, and `-PmodelName`, not `-Pmodel`: Gradle's `Project` already owns
`layout` and `model`, so `hasProperty` is always true and what arrives is the `Project` member, not your
value. A task that reads them silently ignores what you passed.


---

## Agent models are a different job

Almost none of the placement work above applies. The space comes from the model, the agents carry their own
coordinates, the generator frames both, and obstacle walls come out of the model's own geometry. Three
things do need doing, and they are the whole job:

- **Colour has to mean something.** State colours are assigned from a categorical palette in sorted-name
  order, which is deterministic and arbitrary. On an SIR model that produced blue *Infected* and green
  *Susceptible* — worse than no colouring, because red already reads as "bad" and a reader will trust it.
  Set them, and key every other coloured thing in the frame to the same assignments.
- **A field of dots needs a panel.** No clock, no counts, no scale. Put the chrome in a column beside the
  space rather than a strip beneath it: agent spaces are usually square, and a side panel makes the frame
  landscape. Start it *below* the top-right corner, which the screen-space legend owns and no layout can
  move. Give related counts one shared scale so their lengths can be compared — that is what makes an SIR
  crossover or a room emptying legible at a glance.
- **Check how much of the run is dead.** An agent model often finishes long before its replication does. The
  epidemic here cleared at t=38 of 120 and the evacuation at t=14.7 of 60, so most of both animations was a
  finished simulation. That is a model setting, and for a showcase it is worth fixing at the source.

Note that the grid space's origin is **not** where its agents are drawn: an agent's position is its cell
coordinate plus a fixed half-cell centring offset. Moving the space to make room for chrome slides every
agent off the grid it is standing on.

---

## What the generator already does

Worth knowing before polishing, so effort goes where it is needed. From the trace, `auto_layout` recovers:

| From | Accumulator | Used for |
|---|---|---|
| Seize order | `FlowOrder` | resources in flow-ordered columns, and each queue paired to its server |
| `StationEntered` order | `StationFlow` | network stations in a left-to-right lane |
| Entity movement order | `LocationFlow` | which end of a venue the process starts at |
| Entity moves between locations | `EntityRoutes` | the routes drawn between places |
| Arrival-then-seize | `ResourceLocations` | which location each machine stands at |
| `ResourceStateChanged` | `ResourceCapacities` | how wide a machine's block is, so its queue head clears it |
| `QueueLengthChanged` | `QueuePeaks` | how long to draw a queue's extent line |
| Named / bare delays | `DelayStorages` | which storages exist at all |

What it does **not** do, and will not: retitle anything ("DiagnosticWorkers" → "Diagnostics"), decide which
storage style suits a stage, choose colours against the resource-state palette, write a caption, or judge
that one element is more important than another. Those are the polish.
