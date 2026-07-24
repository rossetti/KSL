# Bundle Tools (`kslpkg`) — User Guide

You wrote a model in your own project and it runs from `main` in the IDE. That loop is
fine while you are developing, but it is where things stop: you cannot compare scenarios,
run a designed experiment, optimize it, or browse its results without writing more code.

`kslpkg` is the bridge. It takes the JAR your project already builds and turns it into a
**bundle** — the same kind of JAR the KSL desktop apps and the KSL Server load. One command, and
your model shows up in the apps next to the shipped examples.

Unlike the other guides, this one is a *terminal* tool, so the examples are **command
transcripts** rather than screenshots. Prefer a GUI? The
[Bundle Workbench](bundle-workbench.md) does the same job in a window.

> **You will need:** Java 21 on your `PATH`, a terminal, and a JAR from your own project
> containing at least one `ModelBuilderIfc`. No project yet? Start from
> **KSLProjectTemplate**, which ships a worked example ([§2](#2-what-your-project-needs)).
> New to bundles? See [Common UI → Models and bundles](common-ui.md#models-and-bundles).

## What you'll be able to do

- **Assemble** your project's JAR into a bundle the apps can load.
- **Inspect** any bundle JAR to see the bundles and models it declares.
- Know **what belongs inside** a bundle — and what must not.

---

## 1. At a glance

`kslpkg` has two commands:

| Command | What it does |
|---|---|
| **`assemble`** | Turns a plain **builders JAR** into a **bundle JAR**: a `bundle.toml` manifest plus a per-model `descriptor.json`. |
| **`inspect`** | Prints a human-readable summary of the bundles and models declared in a JAR. |

`assemble` never modifies your JAR — it writes a new one. Both print to the terminal and
set an **exit code** ([§6](#6-reference)) so you can use them in scripts.

**You already have the tool.** Installing the KSL suite puts `kslpkg` here:

| Platform | Location |
|---|---|
| macOS / Linux | `~/Applications/KSL/.support/Tools/kslpkg/kslpkg` |
| Windows | `%LOCALAPPDATA%\Programs\KSL\.support\Tools\kslpkg\kslpkg.cmd` |

Not installed yet? See the [installation guide](install.md). So the commands below read
naturally, alias it (or put that folder on your `PATH`):

```bash
alias kslpkg=~/Applications/KSL/.support/Tools/kslpkg/kslpkg
```

---

## 2. What your project needs

Exactly one thing: **a public `ModelBuilderIfc` with a no-arg constructor**. That is the
whole contract. `kslpkg` scans your JAR, finds every implementation, and packages each one
as a model. Your build needs no KSL-specific plugin or task — the JAR your project already
produces is the input.

```kotlin
class CoffeeShopModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("CoffeeShop", autoCSVReports = false)
        val shop = CoffeeShop(model, numBaristas = 1, name = "Shop")
        model.numberOfReplications = 30
        model.lengthOfReplication = 480.0
        model.curateCatalog {                       // optional, but do it — see below
            input(shop, CoffeeShop::numBaristas) {
                displayName = "Number of Baristas"; unit = "baristas"
            }
            output(shop.systemTime) { displayName = "Avg Time in System"; unit = "min" }
        }
        return model
    }
}
```

**`curateCatalog` is what makes your model pleasant in the apps.** Without it the apps still
work, but they show raw property names. With it they show the display names and units you
chose. The `input(...)` entries are the parameters students can vary; `output(...)` entries
are the responses worth looking at first.

A parameter is overridable in the apps only if its property carries **`@KSLControl`**:

```kotlin
@set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
var numBaristas: Int
    get() = myBarista.initialCapacity
    set(value) { require(value > 0); myBarista.initialCapacity = value }
```

**A complete, working example of all of this ships in `KSLProjectTemplate`** —
`src/main/kotlin/work/CoffeeShop.kt` has the model, its builder, and a `main` for the IDE
loop. If you are starting fresh, copy that project and edit it.

---

## 3. Tutorial — your model into the apps

### Step 1 — Build your project's JAR

Nothing special; the ordinary `jar` task:

```bash
./gradlew jar
# → build/libs/KSLProjectTemplate.jar
```

This JAR contains **only your classes**. That is correct — see [§4](#4-what-belongs-in-a-bundle).

### Step 2 — Assemble it into a bundle

`assemble` builds each discovered model once (to capture its descriptor), writes the
`bundle.toml` manifest and a per-model `descriptor.json`, and validates the result. `--id`
is required and reverse-DNS is recommended; everything else is optional identity:

```bash
kslpkg assemble build/libs/KSLProjectTemplate.jar \
     --id edu.example.mywork \
     --name "My Work" \
     --version 1.0.0 \
     -o my-work.jar --force
```

```text
Assembled edu.example.mywork → my-work.jar
  Models (1):
    - CoffeeShop (CoffeeShop) ← work.CoffeeShopModelBuilder
  Validation: clean (0 errors, 0 warnings)
```

Without `-o`, the output defaults to `<builders-stem>-bundle.jar` next to the input.

### Step 3 — Check what you built

```bash
kslpkg inspect my-work.jar
```

```text
JAR: my-work.jar
Discovery: manifest (META-INF/ksl/bundle.toml)
Bundles: 1

Bundle: edu.example.mywork
  Display name : My Work
  Version      : 1.0.0
  Models       : 1
    - CoffeeShop (CoffeeShop)
        Apps         : SINGLE, SCENARIO, EXPERIMENT
        Has in-JAR descriptor : yes
```

**How to read it.** The **Discovery** line confirms the bundle is manifest-driven. Each
model shows which **apps** it targets and whether it carries an in-JAR **descriptor** —
`assemble` always embeds one, so the apps can read your model's inputs and outputs straight
from the JAR without instantiating anything.

### Step 4 — Use it

Drop it into the `bundles` folder of your KSL working directory:

```bash
cp my-work.jar ~/Documents/KSLWork/bundles/
```

Open **KSL Single** (or Scenario, Experiment, Simopt). Your bundle is in the picker next to
the shipped examples, with the display names and units you curated. Re-assemble and restart
the app whenever you change the model.

That is the whole loop: **edit in the IDE → `./gradlew jar` → `kslpkg assemble` → drop in
`bundles/`.**

> **Shortcut — if you started from `KSLProjectTemplate`.** The template ships two Gradle
> tasks that run this whole loop for you, driving the same `kslpkg`:
>
> ```bash
> ./gradlew deployBundle
> ```
>
> `assembleBundle` builds your JAR and writes the bundle to `build/libs/`; `deployBundle`
> also copies it into your `KSLWork/bundles/`. Set your bundle's `id`, `name`, and `version`
> in the block at the top of the template's `build.gradle.kts`. The tasks find the `kslpkg`
> your KSL install already provides (override with `-Pkslpkg.jar=…`, `KSL_HOME`, or
> `-Pksl.workspace=…`); an ordinary `./gradlew build` is unaffected when the suite is not
> installed. See `KSLProjectTemplate/README.md`.

---

## 4. What belongs in a bundle

**A bundle carries your classes and nothing else.** It does *not* carry KSL: at load time
the apps hand your bundle their own classloader, so your model resolves `KSLCore` and every
library the suite ships from the suite itself. This is deliberate — it is why bundles are
kilobytes rather than tens of megabytes, and why they pick up suite updates automatically.

The consequence is the one thing worth knowing before you get bitten by it:

> **If your model uses a library the KSL suite does not ship, the bundle will not find it
> at run time.** Your project compiles, and the JAR is fine — but the class simply is not
> there when the app loads your model.

How it surfaces depends on *where* you use that library:

| Where you touch it | What happens |
|---|---|
| Inside `build(...)` | `assemble` **fails** with exit 1 and names the missing class — you find out immediately. |
| Anywhere else (inside a process, an event, a response) | `assemble` reports **`Validation: clean`**, and the app throws `NoClassDefFoundError` when the model *runs*. |

So a clean `assemble` is not by itself proof that an extra dependency is safe. If you add a
third-party library to your project, **run the model once from the app** before relying on
it. In practice this is rarely a problem: KSL ships a large surface (statistics, plotting,
Excel, the databases, JSON), so most models need nothing extra.

If you do need an outside library, the options are: prefer a KSL equivalent, or vendor the
library's classes into your own JAR (a "fat" builders JAR) before assembling — at the cost
of the size and update benefits above.

---

## 5. Common tasks

| Task | Command |
|---|---|
| Package your project's JAR | `kslpkg assemble build/libs/<your>.jar --id <bundleId>` |
| See what's in a bundle JAR | `kslpkg inspect <jar>` |
| Choose the output path | add `-o <out.jar>` |
| Overwrite an existing output | add `--force` |
| Drop a helper/closure from the model set | `--exclude <modelId,...>` |
| Add identity metadata | `--name`, `--description`, `--version`, `--author`, `--homepage`, `--license`, `--tag` |
| Check the tool version | `kslpkg --version` |
| One-command build + deploy (from `KSLProjectTemplate`) | `./gradlew deployBundle` |
| Wire `assemble` into another Gradle build | see the `JavaExec` pattern in `KSLBundleTools/README.md` |

---

## 6. Reference

### Commands

- **`assemble <builders.jar> --id <bundleId> [options]`** — turn a plain builders JAR into
  a bundle JAR (`bundle.toml` + per-model `descriptor.json`). `--id` is required; identity
  options are `--name --description --version --author --homepage --license --tag`.
  `--exclude <id,...>` drops models by `modelId` — useful when a builder in your JAR is a
  shared helper rather than a standalone model (it stays in the JAR, so anything that
  delegates to it still resolves; it just is not declared as a model). The default output
  is `<builders-stem>-bundle.jar` next to the input; `-o` sets it, `--force` overwrites.
- **`inspect <jar>`** — summarize the bundle and models a JAR declares (via its
  `bundle.toml` manifest). A JAR with no manifest is not an error: it reports that no
  bundle was found and exits `0`.

Run `kslpkg` with no arguments for the full usage message.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Ran to completion as intended (including `inspect` on a JAR with no bundle). |
| `1` | User-input error: bad arguments, a missing file, a missing `--id`, an output collision (no `--force`), a builders JAR with no usable models, or a validation error. |
| `2` | Internal failure: a model threw while building during descriptor extraction, or an I/O write failed. |

Distinguishing `1` from `2` lets scripts treat "bad input" and "tool broke" differently.

---

## 7. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `assemble: no ModelBuilderIfc implementations found` | Your JAR has no builder — or it has one that **failed to load**, in which case the message names it and the class it could not find. | Add a public, no-arg `ModelBuilderIfc`. If a class is named, see [§4](#4-what-belongs-in-a-bundle). |
| `NoClassDefFoundError` when the app runs your model | Your model uses a library the suite does not ship. | See [§4](#4-what-belongs-in-a-bundle). |
| `assemble: --id <bundleId> is required` | `--id` was omitted. | Pass `--id <bundleId>` (reverse-DNS recommended). |
| `assemble` refuses to write | The output file exists. | Add `--force`, or choose a different `-o` path. |
| `assemble` exits with code 2 | A model threw while building. | Fix the model's `build(...)`; every declared model must build to have its descriptor extracted. |
| `inspect` says "no bundle" | The JAR has no `bundle.toml` (it's a plain builders JAR, or not a bundle at all). | Run `assemble` on it first, then inspect the result. |
| Your model isn't in the app's picker | It went to the wrong folder, or the app wasn't restarted. | Confirm it's in `<KSLWork>/bundles/`; restart the app, or use **Bundles → Load JAR…**. |
| The app shows raw property names | The builder has no `curateCatalog` block. | Add one ([§2](#2-what-your-project-needs)). |
| `java: command not found` | Java isn't on your `PATH`. | Install/select JDK 21 and re-open the terminal. |

---

## 8. See also

- [Bundle Workbench](bundle-workbench.md) — the desktop-app companion: the same
  packaging job (open → identity → catalog → validate → assemble) in a guided GUI.
- `KSLBundleTools/README.md` — the full tool reference, Gradle/IntelliJ wiring, and the
  `ksl.app.bundle` SPI it serves.
- [Common UI & concepts](common-ui.md) — how the desktop apps consume bundles.
- The app guides — [Single](single.md), [Scenario](scenario.md), [Experiment](experiment.md), [Simopt](simopt.md).
