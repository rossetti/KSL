# Common UI & Concepts (all KSL desktop apps)

The KSL desktop applications — **Single**, **Scenario**, **Experiment**, **Simopt**,
**Results**, and **Distribution** — share a common look and a common set of building
blocks (from the `KSLAppSwingCommon` module). Learn these once here; each app guide
links back to this page instead of repeating them.

---

## Core concepts

### Models and bundles

A **model** is a KSL simulation (for example, an M/M/1 queue). The apps do not have
models compiled into them. Instead they load **bundles** — JAR files that advertise one
or more models through a service interface. At launch each app discovers bundles from:

- the application classpath (none in a released app), and
- your personal bundle folder, `~/.ksl/bundles/`.

You can also add a bundle mid-session with **Bundles → Load JAR…**. Bundles are produced
with the `kslpkg` command-line tool — see the [Bundle Tools guide](bundle-tools.md).

### The workspace (working directory)

Everything an app writes — databases, CSV files, reports, saved configurations — goes
under your **working directory** (the *workspace*). The current workspace is always shown
in the **status bar** at the bottom of the window. Change it from **File → Set Working
Directory…**, and revisit recent ones from **File → Recent Directories**.

Within the workspace, each analysis gets its own subfolder (named from the **Analysis /
Output Name** you set), typically containing:

| Subfolder | Holds |
|---|---|
| `configs/` | saved `.toml` configurations |
| `output/` | databases, CSV, plot data |
| `reports/` | rendered HTML / Markdown / text reports |

### Configurations vs. results

A **configuration** is the *input* you set up (parameters, overrides, scenarios). Saving
a configuration (**File → Save**, `Ctrl/Cmd+S`) writes a small `.toml` file you can reopen
later. It does **not** save model output. **Results** are produced when you run, and are
written as databases, CSVs, and reports.

---

## Shared window elements

### Menu bar

| Menu | Typical contents |
|---|---|
| **File** | New / Reset, Open, Recent, Save, Save As, Set Working Directory, Recent Directories, Exit |
| **Bundles** *(bundle mode)* | Load JAR…, Loaded Bundles… |
| **View** | Appearance (theme) |
| **Help** | About |

### Bundle / model picker

When an app needs you to choose a model, it shows a two-step picker: choose a **bundle**,
then a **model**, with a read-only info panel summarizing that model's controls, random
variables, responses, and run defaults. The **Loaded Bundles…** dialog lists everything
currently loaded (and notes any duplicate copies that were ignored — newest wins).

### Theme (Appearance)

**View → Appearance** switches between **Light**, **Dark**, and **System** themes
(FlatLaf). The choice is cosmetic and is remembered between sessions. *(Guide screenshots
use the Light theme.)*

### Run console

Apps that run simulations include a collapsible **console drawer** above the status bar.
It streams run events (start, per-replication progress, completion, errors). Collapsed, its
header shows per-run **INFO / WARN / ERR** counts so you get a glance signal without
expanding it.

### Execution mode (Sequential / Concurrent)

Batch-running apps (Scenario, Experiment, Simopt) offer an **execution-mode** toggle.
*Sequential* runs items one at a time (easiest to follow); *Concurrent* runs several at
once (faster on multi-core machines). The control locks while a run is in flight.

### Notifications

Transient **toast** messages appear at the bottom-right for file operations, run
completion, and warnings/errors. They auto-dismiss; click one to dismiss it early.

### Validation banner

When a configuration has problems, a **health banner** appears at the top of the editor
summarizing the findings; each finding has a **Jump to source** button that focuses the
offending field. A green state means you're clear to run.

### Override fields

Where you override a model's defaults, fields are **dual-mode**: leave a field on its
*default* to inherit the model's value, or switch it to a typed value. Booleans render as
a **Default · Yes · No** tri-state; string controls with a fixed set of allowed values
render as a drop-down; structured values use a JSON editor.

---

## Reports: what you get and how to read them

All apps render results through the same KSL reporting engine, which can emit **HTML**
(opens in your browser), **Markdown**, and **plain text**. A typical simulation report
contains:

- a **run summary** (replications completed, run length, ending status);
- **across-replication statistics** for each response — count, average, standard
  deviation, standard error, and a confidence-interval half-width;
- **histograms** and **time-series** plots for tracked responses.

The per-app guides show real examples of these tables and charts.

> **Tip — confidence intervals.** Most tables report an average together with a 95%
> **half-width**. The interval `average ± half-width` is where the true long-run mean
> plausibly lies. A smaller half-width means a more precise estimate; you shrink it by
> running more replications or a longer run.

---

## See also

- [KSL Book](https://rossetti.github.io/KSLBook/) — the simulation concepts behind the apps.
- The package guides under [`docs/guides/`](../README.md) — for building models in code.
