# Showcase animation layouts

Polished `.lay.json` layouts for the animations used in the repository README and in the
[Animation app guide](../guides/apps/animation.md).

These are **documents, not code**. A layout is deliberately not expressed in the `AnimationBuilder`
DSL, because the DSL cannot express three of the things that polishing depends on most:

| Layout section | Swing editor / MCP | `AnimationBuilder` DSL |
|---|---|---|
| `labels` — retitle, offset, or hide a label | yes | **no** |
| `processColors` — tint entities by current process | yes | **no** |
| `conveyors` — authored belt routes and waypoints | yes | **no** |

Label overrides alone account for most of the difference between a layout that is legible and one that
is not, because a station typically attracts four labels at the same point — its own name, its
resource's name, its queue's name and count, and any worker parked on it.

## Reproducing one

A trace and a layout are separate files bound by **element name**, so the trace is captured once and the
layout can then be edited freely — no recompiling and no re-running the model. That is what makes
polishing affordable.

```bash
# 1. capture the trace and an auto-layout starting point (never overwrites an existing layout)
./gradlew :KSLExamples:showcaseCapture -PmodelName=Example13MovableResources -Pout=build/showcase

# 2. copy the polished layout over the starting point, or edit in place
cp docs/animations/layouts/Example13MovableResources.lay.json build/showcase/

# 3. look at it — a contact sheet of frames across the run
./gradlew :KSLAppSwingAnimation:renderFrames \
  -Ptrace=build/showcase/Example13MovableResources.atf \
  -PlayoutFile=docs/animations/layouts/Example13MovableResources.lay.json \
  -Pframes=6 -Pout=build/showcase/sheet -Pw=1000 -Ph=620

# 4. or play it in a browser
./gradlew :KSLApp:exportAnimationHtml \
  -Ptrace=build/showcase/Example13MovableResources.atf \
  -PlayoutFile=docs/animations/layouts/Example13MovableResources.lay.json \
  -Pout=build/showcase/Example13.html
```

Note `-PlayoutFile`, not `-Playout`: Gradle's `Project` already owns a `layout` property, so a
`-Playout=` value never reaches the task.

## What polishing actually involves

Lessons from the first model, which generalise:

- **Hide redundant labels before moving anything.** A station's location name usually repeats what its
  resource label already says, and a queue's name repeats it again — the queue's *count* is the only
  informative part.
- **Offsets must be derived from glyph size**, not fixed. A 30-unit resource box and a 15-unit worker
  triangle need different clearance, and a single constant collides with one or the other.
- **Check mover colours against the resource state palette.** Red means busy and green means idle, so a
  red worker parked on a busy station camouflages and a green one reads as a state change. Pick hues the
  state palette does not use.
- **Drop internal elements.** A resource pool's own queue (workers waiting to be assigned) says nothing
  about the system being modelled, and its extent line is often the longest thing on screen.
- **A queue's extent line is `spacing × maxShown`.** Leaving `maxShown` generous advertises a capacity
  that is never reached and dominates the picture.
- **A station is "queue + resource", and it has to read left to right.** Waiting members on the left, the
  head of the line, then the server: `growthDegrees = 180.0` with the head just left of the resource. This
  is what the auto-layout already does, and it beats a queue hanging below its station, which grows a
  vertical stalk and carries the eye the wrong way.
- **Size the elements to the arrangement, not to the old canvas.** Sizes carried over from a hand-placed
  layout leave the stations as specks once they are spread over a real distance matrix. A block a fifth of
  the arrangement's width reads as a machine; a block a twentieth reads as a dot.
- **A resource is one cell per unit of capacity**, in a row centred on its position, so its half-width is
  `capacity × size / 2`. Assuming a single cell tucks a multi-server station's queue underneath the block.
  Capacity is a property of the run, so it comes from the trace, not the layout.
- **A movable resource's drawn position resolves through the *location* of the same name.** A machine
  sitting exactly on its location therefore has workers parked on top of it, hiding the part being worked
  on. Offsetting the machine leaves the location as the spot on the floor where a worker stands.
- **Draw the routes.** Stations alone float in white space with nothing to connect them. Reading the
  station-to-station moves that *parts* actually made out of the trace gives the picture a floor plan and
  costs no invention. Exclude the workers' own repositioning: a worker will travel anywhere to fetch its
  next job, so including it makes the graph nearly complete and says nothing about how the shop is routed.

### On placement produced by MDS

The auto-layout places stations by classical multidimensional scaling of the model's own distance matrix,
which is worth preserving — the picture then carries real information. MDS fixes only the *shape*: a
configuration is determined up to rotation, reflection and translation, so re-orienting it to read left to
right is free, and a script can search all 360° × both reflections for the orientation that best does so.
Assert that pairwise distances survive the transform; a sign error yields a plausible-looking picture with
the wrong distances in it.

What re-orientation cannot fix is a station the model itself places far from the others. In
`Example13MovableResources`, Diagnostics→Test 3 is the largest distance in the matrix, so Test 3 reads as
remote in every orientation. That is the model, not the layout. The choice is to keep the placement
faithful and let scale and routes carry the composition, or to abandon MDS and place stations by hand —
not to quietly distort the distances while still calling them a distance matrix.
