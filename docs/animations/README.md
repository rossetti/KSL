# Showcase animation layouts

Polished `.lay.json` layouts for the animations used in the repository README and in the
[Animation app guide](../guides/apps/animation.md).

**[How to polish one →](polishing-playbook.md)** — the defect catalogue, in fix order, for both the
desktop app and the MCP server.

## What is here

| File | What it is |
|---|---|
| `layouts/*.lay.json` | The polished layouts. Committed artifacts. |
| | `Example03GridEpidemic` · `Example05PedestrianCrowd` · `Example12StemFairStorage` · `Example13MovableResources` · `Example17TandemBlocking` · `Example18ConveyorTestRepair` |
| `polish-<model>.py` | The script that produces one, from the auto-layout starting point. |
| `polishing-playbook.md` | The reusable knowledge: what goes wrong, in what order to fix it. |

These are **documents, not code**. A layout is deliberately not expressed in the `AnimationBuilder` DSL,
because the DSL cannot express three of the things polishing depends on most:

| Layout section | Swing editor / MCP | `AnimationBuilder` DSL |
|---|---|---|
| `labels` — retitle, offset, or hide a label | yes | **no** |
| `processColors` — tint entities by current process | yes | **no** |
| `conveyors` — authored belt routes and waypoints | yes | **no** |

Label overrides alone account for most of the difference between a layout that is legible and one that is
not, because a station typically attracts four labels at the same point.

Each polish is kept as a **script that transforms the starting layout** rather than as a hand-edited JSON
blob, so every change carries its reason and the layout can be regenerated if the model's geometry changes.

## Turning a layout into an image

```bash
# stills — one PNG per frame, spread across the run; each is cropped to its own content
./gradlew :KSLAppSwingAnimation:renderFrames \
  -Ptrace=build/showcase/<name>.atf -PlayoutFile=docs/animations/layouts/<name>.lay.json \
  -Pframes=6 -Pout=build/showcase/sheet -Pw=1000 -Ph=700

# a looping animation
./gradlew :KSLAppSwingAnimation:renderGif \
  -Ptrace=build/showcase/<name>.atf -PlayoutFile=docs/animations/layouts/<name>.lay.json \
  -Pout=build/showcase/<name>.gif -Pframes=45 -Pw=720 -Ph=460 -Pdelay=7
```

The gallery in the [Animation app guide](../guides/apps/animation.md) uses stills. The GIF writer is
there for when motion is wanted: it goes through `javax.imageio`, so it needs no encoder installed, and
it writes only the rectangle of each frame that changed. Two things to know before using it —

- **GIF is 256 colours per frame**, so a smooth gradient (a flow-field heatmap) bands. Flat-filled models
  are unaffected.
- **Frame differencing only helps when change is localised.** A model whose glyphs move all over its space
  has a per-frame dirty rectangle covering nearly the whole canvas, and a full-size animation of one runs
  to megabytes. Shrink the dimensions and the frame count rather than expecting the encoder to save you.

Pass `-Pcrop=false` to `renderFrames` for a sequence: by default each frame is cropped to its own content,
which is right for a single still and makes an assembled animation jitter.

## Getting the starting material

```bash
./gradlew :KSLExamples:showcaseCapture -PmodelName=Example13MovableResources -Pout=build/showcase
./gradlew :KSLExamples:showcaseCapture -PmodelName=list      # everything capturable
```

This writes three files into `-Pout`:

| File | What it is |
|---|---|
| `<name>.atf` | The captured trace. Every overlay is on — deciding which ones help is a display choice made later while polishing, and re-capturing to add one back would invalidate the polishing already done against the old trace. |
| `<name>.lay.json` | An auto-layout starting point — **written only when absent**. Once polishing has started, that file is the work; a re-capture must not silently discard it. Delete it deliberately to start over. |
| `<name>.dsl.lay.json` | The example's own `AnimationBuilder` layout, for comparison. It carries the author's intent (which elements matter, what they are called) where the auto-layout carries what the run actually did. |

All eighteen bundle models can be captured. The tool lists them all rather than a curated subset: which
models are worth showcasing is a judgement that changes, but which ones *can* be captured is a fact about
the bundle.
