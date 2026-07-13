# Bundle Tools (`kslpkg`) — User Guide

`kslpkg` is a small **command-line** tool for working with **KSL model bundles** — the JAR
files that package one or more compiled models so the desktop apps (Single, Scenario,
Experiment, Simopt) and the servers can load them. Unlike the other guides, this one is a
*terminal* tool, so the examples are **command transcripts** rather than screenshots. Prefer
a GUI? The [Bundle Workbench](bundle-workbench.md) desktop app does the same packaging job.

> **You will need:** Java 21 on your `PATH` and a terminal. To follow along you also need
> the `kslpkg` tool and a **builders JAR** (both built below). New to bundles? See
> [Common UI → Models and bundles](common-ui.md#models-and-bundles).

## What you'll be able to do

- Build the `kslpkg` tool.
- **Inspect** a bundle JAR to see the bundles and models it declares.
- **Assemble** a plain **builders JAR** (your compiled model classes) into a
  self-describing **bundle JAR** — a `bundle.toml` manifest plus a per-model descriptor.

---

## 1. At a glance

`kslpkg` has two commands:

| Command | What it does |
|---|---|
| **`inspect`** | Prints a human-readable summary of the bundles and models declared in a JAR. |
| **`assemble`** | Turns a plain **builders JAR** into a **bundle JAR**: a `bundle.toml` manifest plus a per-model `descriptor.json`. |

Running it with no arguments prints the usage:

```text
$ kslpkg
kslpkg — KSL bundle authoring tool

Usage:
  kslpkg inspect <jar>
      Print a human-readable summary of the bundles in <jar>.

  kslpkg assemble <builders.jar> --id <bundleId> [options]
      Turn a plain builders JAR (one or more ksl.simulation.ModelBuilderIfc
      classes) into a self-describing bundle JAR: a bundle.toml manifest
      plus a per-model descriptor.json. --id is required (reverse-DNS
      recommended, e.g. edu.uark.ksl.queueing); other identity is optional,
      from [--name --description --version --author --homepage
      --license --tag <t>]. --exclude <id,...> drops discovered models by
      modelId (e.g. a shared closure embedded for runtime, not a model).
      Default output is <builders-stem>-bundle.jar next to the input;
      -o sets it, --force overwrites an existing file.

  kslpkg --help        Print this message
  kslpkg --version     Print the tool version
```

---

## 2. Before you begin

**Build the tool.** From the repository root, produce the self-contained fat JAR:

```bash
./gradlew :KSLBundleTools:shadowJar
# → KSLBundleTools/build/libs/KSLBundleTools-1.0-SNAPSHOT-all.jar
```

For convenience, alias `kslpkg` to it so the commands below read naturally:

```bash
alias kslpkg='java -jar KSLBundleTools/build/libs/KSLBundleTools-1.0-SNAPSHOT-all.jar'
```

> **Native launcher (optional).** `./gradlew :KSLBundleTools:runtime` builds a
> self-contained native CLI with a bundled JRE at `KSLBundleTools/build/image/bin/kslpkg`
> — run it as `kslpkg`, no `java -jar` needed. The `kslpkgZip` task packages that image
> for distribution; it's how `kslpkg` ships on GitHub Releases.

**Get a builders JAR** to work with. A *builders JAR* is just your compiled
`ModelBuilderIfc` classes, with no manifest. This guide uses the KSL book models:

```bash
./gradlew :KSLExamples:bookBuildersJar
# → KSLExamples/build/libs/book-builders.jar   (17 model builders)
```

---

## 3. The commands

`inspect` only reads a JAR; `assemble` reads a builders JAR and writes a **new** bundle JAR
(the input is never modified). Each prints to the terminal and sets an **exit code** (see
[§6](#6-reference)) so you can use them in scripts.

---

## 4. Tutorial

### Step 1 — Assemble a bundle JAR

`assemble` builds each discovered model once (to capture its descriptor), writes a
`bundle.toml` manifest and a per-model `descriptor.json`, and validates the result. `--id`
is required; here we also name the bundle, set a version, and **exclude** a helper builder
that is a shared closure rather than a standalone model:

```bash
kslpkg assemble KSLExamples/build/libs/book-builders.jar \
     --id edu.uark.ksl.book-examples \
     --name "KSL Book Examples" \
     --version 1.0.0 \
     --exclude BuildTwoEchelonModel \
     -o book-examples.jar --force
```

```text
Assembled edu.uark.ksl.book-examples → book-examples.jar
  Models (16):
    - DriveThroughPharmacyWithQ (DriveThroughPharmacyWithQ) → ksl.examples.general.bookbundle.DriveThroughPharmacyWithQModelBuilder
    - TandemQueue (TandemQueue) → ksl.examples.general.bookbundle.TandemQueueModelBuilder
    - WalkInHealthClinic (WalkInHealthClinic) → ksl.examples.general.bookbundle.WalkInHealthClinicModelBuilder
    ... (16 models)
    - (excluded) BuildTwoEchelonModel → ksl.examples.general.models.inventory.BuildTwoEchelonModel
  Validation: clean (0 errors, 0 warnings)
```

The excluded builder stays in the JAR (so any model that delegates to it still resolves at
runtime) but is not declared as a bundle model. Without `-o`, the output defaults to
`book-builders-bundle.jar` next to the input.

### Step 2 — Inspect the result

`inspect` reads a bundle JAR's manifest and lists everything it declares:

```bash
kslpkg inspect book-examples.jar
```

```text
JAR: book-examples.jar
Discovery: manifest (META-INF/ksl/bundle.toml)
Bundles: 1

Bundle: edu.uark.ksl.book-examples
  Display name : KSL Book Examples
  Version      : 1.0.0
  KSL API      : (none)
  Source JAR   : book-examples.jar
  Models       : 16
    - DriveThroughPharmacyWithQ (DriveThroughPharmacyWithQ)
        Apps         : SINGLE, SCENARIO, EXPERIMENT
        Has in-JAR descriptor : yes
    - TandemQueue (TandemQueue)
        Apps         : SINGLE, SCENARIO, EXPERIMENT
        Has in-JAR descriptor : yes
    ... (16 models)
```

**How to read it.** The **Discovery** line confirms the bundle is manifest-driven
(`META-INF/ksl/bundle.toml`). Each **model** shows which **apps** it targets (from the
manifest) and whether it carries an in-JAR **descriptor** — `assemble` always embeds one,
so consumers can read a model's input/output surface straight from the JAR without
instantiating a Kotlin class. Drop `book-examples.jar` into `<KSLWork>/bundles/` and the
desktop apps and servers will discover its models.

---

## 5. Common tasks

| Task | Command |
|---|---|
| See what's in a bundle JAR | `kslpkg inspect <jar>` |
| Assemble a builders JAR into a bundle | `kslpkg assemble <builders.jar> --id <bundleId>` |
| Choose the output path | add `-o <out.jar>` |
| Overwrite an existing output | add `--force` |
| Drop a helper/closure from the model set | `--exclude <modelId,...>` |
| Add identity metadata | `--name`, `--description`, `--version`, `--author`, `--homepage`, `--license`, `--tag` |
| Check the tool version | `kslpkg --version` |
| Wire `assemble` into a Gradle build | see the `JavaExec` pattern in `KSLBundleTools/README.md` |

---

## 6. Reference

### Commands

- **`inspect <jar>`** — summarize the bundle and models a JAR declares (via its
  `bundle.toml` manifest). A JAR with no manifest is not an error: it reports that no
  bundle was found and exits `0`.
- **`assemble <builders.jar> --id <bundleId> [options]`** — turn a plain builders JAR into
  a bundle JAR (`bundle.toml` + per-model `descriptor.json`). `--id` is required; identity
  options are `--name --description --version --author --homepage --license --tag`.
  `--exclude <id,...>` drops models by `modelId`. The default output is
  `<builders-stem>-bundle.jar` next to the input; `-o` sets it, `--force` overwrites.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Ran to completion as intended (including `inspect` on a JAR with no bundle). |
| `1` | User-input error: bad arguments, a missing file, a missing `--id`, an output collision (no `--force`), a builders JAR with no models, or a validation error. |
| `2` | Internal failure: a model threw while building during descriptor extraction, or an I/O write failed. |

Distinguishing `1` from `2` lets scripts treat "bad input" and "tool broke" differently.

---

## 7. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `inspect` says "no bundle" | The JAR has no `bundle.toml` manifest (it's a plain builders JAR, or not a bundle). | Run `assemble` on a builders JAR to produce a bundle, then inspect that. |
| `assemble: --id <bundleId> is required` | `--id` was omitted. | Pass `--id <bundleId>` (reverse-DNS recommended, e.g. `edu.uark.ksl.queueing`). |
| `assemble` refuses to write | The output file exists. | Add `--force`, or choose a different `-o` path. |
| `assemble` finds no models | The JAR has no `ModelBuilderIfc` classes. | Point at a real builders JAR (e.g. `book-builders.jar`). |
| `assemble` exits with code 2 | A model threw while building. | Fix the model's `build(...)`; every declared model must build to have its descriptor extracted. |
| `java: command not found` | Java isn't on your `PATH`. | Install/select JDK 21 and re-open the terminal. |

---

## 8. See also

- [Bundle Workbench](bundle-workbench.md) — the desktop-app companion: the same
  packaging job (open → identity → catalog → validate → assemble) in a guided GUI.
- `KSLBundleTools/README.md` — the full tool reference, Gradle/IntelliJ wiring, and the
  `ksl.app.bundle` SPI it serves.
- [Common UI & concepts](common-ui.md) — how the desktop apps consume bundles.
- The app guides — [Single](single.md), [Scenario](scenario.md), [Experiment](experiment.md), [Simopt](simopt.md).
