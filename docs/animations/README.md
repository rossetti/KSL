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
