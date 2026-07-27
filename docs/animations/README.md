# Showcase animation layouts

The polished animation layouts that **ship with the KSL suite** — one for every model the animation bundle
carries — and the scripts that produce them. Also the source of the gallery in the
[Animation app guide](../guides/apps/animation.md).

**[How to polish one →](polishing-playbook.md)** — the defect catalogue, in fix order, for both the
desktop app and the MCP server.

## What is here

| File | What it is |
|---|---|
| `layouts/<bundleId>/<modelId>.lay.toml` | The polished layouts, as they ship. Committed artifacts, one per model the bundle carries. |
| `polish-<model>.py` | The script that produces one, from the auto-layout starting point. |
| `polishkit.py` | The mechanics the scripts share — loading, trace facts, the JSON shapes for chrome. |
| `polishing-playbook.md` | The reusable knowledge: what goes wrong, in what order to fix it. |

A layout names one model's queues, resources and locations, so it means nothing for any other model. The
shipped ones are keyed by the pair that identifies a model exactly — its **bundle id** and its **model id**
— which is also the pair the animation app holds when a model is open, so *Layout ▸ Use Shipped Layout*
is a path lookup rather than a search.

They ship as **TOML**, because `.lay.toml` is what the app writes when a student saves a layout: ours and
theirs should be the same kind of file, and it is the one worth opening in an editor. The polish scripts
write JSON into `build/showcase/polished/` and
`./gradlew :KSLExamples:publishAnimationLayouts` converts it through the app's own codec, so the shipped
form cannot drift from what the app reads. The bundle's manifest decides which models need one, so a model
added to the bundle fails that step until it has a layout.

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
./gradlew :KSLAppSwingAnimation:renderFrames \
  -Ptrace=build/showcase/<name>.atf -PlayoutFile=build/showcase/polished/<name>.lay.json \
  -Pframes=6 -Pout=build/showcase/sheet -Pw=1000 -Ph=700
```

One PNG per frame, spread across the run, each cropped to its own content. Render several and look at
them together: a layout that reads well at `t = 0`, with everything idle and every queue empty, can be
unreadable at steady state. The gallery in the [Animation app guide](../guides/apps/animation.md) is
built from frames chosen this way.

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
