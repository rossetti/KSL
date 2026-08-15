---
name: ksl-simulation
description: >-
  Drive the KSL (Kotlin Simulation Library) MCP server — run and analyze simulations, and answer
  questions from the KSL textbook and source. Use when the user wants to run a simulation model,
  compare staffing/design scenarios and pick the best (MCB), design an experiment, optimize inputs,
  fit an input distribution, or look up a KSL concept, class, or API. Explains how to discover models,
  route to the right tool, and author run/scenario configurations correctly on the first try.
---

# Working with the KSL simulation tools

The KSL MCP server ("ksl-suite") exposes three surfaces — **simulation**, **textbook**, **source code** —
on one endpoint. The server's connect-time instructions tell you *which family* of tools to reach for;
this skill covers the part they don't: **how to discover models and author configurations without
trial-and-error** (the place agents actually get stuck).

## 1. Orient — never guess names
- Start with **`get_started`** — it returns the live bundle + model catalog and routes you to a workflow.
- **`list_models`** (per bundle) and **`describe_model(bundleId, modelId)`** give the exact model ids,
  input keys, and response names. Read `describe_model` **before** setting any input.

## 2. Route by intent
| The user wants… | Tool path |
|---|---|
| A concept / method / homework answer | `search_textbook` → `get_section`; **cite the section URL** |
| A KSL class, function, or API | `search_code` → `get_class` / `get_example`; **cite the source URL** — never invent a signature |
| Run ONE configuration | `run_model` |
| Compare scenarios — "which is best?" | a scenario batch via `run_config` (database on) → `db_compare` (MCB) → `db_compare_report` (box/CI plots) |
| A designed experiment | `run_experiment` (needs **≥ 2 factors**) |
| Optimize inputs | `run_optimization` |
| Fit a distribution to data | `fit_dataset` |
| The report / plots / exports of a result | `get_artifacts` → `get_artifact` |

## 3. Controls vs. random-variable parameters (the #1 trap)
`describe_model` lists the inputs. There are **two kinds**, and they go in **different places**:
- **Controls** — capacities, counts, flags → `controlOverrides.numericControls` / `stringControls`.
- **Distribution parameters** — a **mean service time**, an arrival rate, etc. — are **RV parameters**,
  set via **`rvOverrides`** `{rvName, paramName, value}`. Putting a distribution mean in `numericControls`
  silently does nothing.

## 4. Author configs the safe way — scaffold, don't hand-write
Hand-authoring a `RunConfiguration` is the main source of validation errors. Instead:
1. **`run_template(bundleId, modelId)`** → a correct scaffold with every control descriptor at its default.
2. Edit only what changes: replication count, control values, `rvOverrides`, `outputConfig.enableKSLDatabase`.
3. **`validate_run_config`** → fix any errors → **`preview_run_config`** (confirms the workload) → **`run_config`**.

Rules the scaffold already satisfies — don't undo them:
- **No top-level `name`** — the name lives on each `[[scenarios]]` entry (unique within the document).
- Each numericControl keeps its **full descriptor**, including `comment` and `modelName` (both required).
- Distribution parameters go in `rvOverrides`, not controls (see §3).
- To compare, put every scenario in **one** `run_config` batch → they run with **common random numbers**
  (paired), which is what makes MCB tight. Do **not** approximate a comparison with repeated `run_model`
  calls — those use independent streams and produce no MCB and no plots.
- Model reference: `byBundleAndModelId {bundleId, modelId}` (what the desktop apps save, so the same file
  opens there) or `byProviderId {providerId}` (simplest when you are only ever running on the server).
  Both run on the server. **If you use `byBundleAndModelId`, the bundle must also be declared in the
  document's `[[bundleRefs]]`** or validation fails with `SCENARIO_BUNDLE_REF_MISSING` — the scaffold and
  the shipped example both include it. The declared `paths` are the authoring machine's; they are never
  checked for existence, which is what lets one file run on another machine.

You can submit the document either as a JSON object **or as the TOML text itself** — paste the contents
of a `.toml` saved by a desktop app straight into `config`. The server parses both.

A ready-to-adapt scenario comparison ships with this skill: **`examples/scenario-compare.toml`** — copy it,
swap the model id and the two control values, and run it with `run_config`.

## 5. Results, comparisons, artifacts
- A comparison's answer: `db_compare` names the best alternative and the pairwise differences;
  `db_compare_report` renders the box plot + CI plot as a downloadable artifact.
- `run_model` returns full statistics but **no plot images** — plots come from `fit_dataset`,
  `run_experiment`, and `db_compare_report`.
- Artifacts carry a `url` as well as a `path`. **Hand the user the `url`** — it opens in a browser, while
  the path only works on the machine running the server. `db_compare_report` prints the link directly.
- `outputConfig.reports` does **not** produce artifacts on the server; reports and plots come from
  `enableWelchAnalysis` / `enableResponseTrace` and from the `db_*` report tools.

## 6. Quick reference
- Unsure what exists or what to do → `get_started`.
- Concept / API question → search **first** (`search_textbook` / `search_code`), then cite the URL.
- Any run or scenario config → `run_template` → edit → `validate` → `preview` → `run_config`.
- "Which is better?" → one `run_config` batch (database on) → `db_compare` / `db_compare_report`.
