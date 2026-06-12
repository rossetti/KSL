# Bundle Tools (`kslpkg`) — User Guide

`kslpkg` is a small **command-line** tool for working with **KSL model bundles** — the JAR
files that package one or more compiled models so the desktop apps (Single, Scenario,
Experiment, Simopt) can load them. Unlike the other guides, this one is a *terminal* tool,
so the examples are **command transcripts** rather than screenshots.

> **You will need:** Java 21 on your `PATH` and a terminal. To follow along you also need
> the `kslpkg.jar` tool and a bundle JAR (both built below). New to bundles? See
> [Common UI → Models and bundles](common-ui.md#models-and-bundles).

## What you'll be able to do

- Build the `kslpkg` tool.
- **Inspect** a bundle JAR to see the bundles and models it contains.
- **Enrich** a bundle JAR so consumers can read each model's input/output surface without
  running it.

---

## 1. At a glance

`kslpkg` has two commands:

| Command | What it does |
|---|---|
| **`inspect`** | Prints a human-readable summary of the bundles, models, and capabilities in a JAR. |
| **`enrich`** | Builds each model once to extract its descriptor, and writes a copy of the JAR with those descriptors embedded. |

Running it with no arguments prints the usage:

```text
$ java -jar kslpkg.jar
kslpkg — KSL bundle authoring tool

Usage:
  kslpkg inspect <jar>
      Print a human-readable summary of the bundles in <jar>.

  kslpkg enrich <input.jar> [-o <output.jar>] [--force]
      Extract a ModelDescriptor for every bundled model and write
      a copy of <input.jar> with the descriptors embedded under
      META-INF/ksl/models/<modelId>/descriptor.json. ...

  kslpkg --help        Print this message
  kslpkg --version     Print the tool version
```

---

## 2. Before you begin

**Build the tool** — from the repository root, produce the self-contained `kslpkg.jar`:

```bash
./gradlew :KSLBundleTools:shadowJar
# → KSLBundleTools/build/libs/kslpkg.jar
```

**Get a bundle JAR** to work with. This guide uses the example bundle built from
`KSLExamples`:

```bash
./gradlew :KSLExamples:jar
# → KSLExamples/build/libs/KSLExamples-1.0-SNAPSHOT.jar
```

---

## 3. The commands

Both commands take a JAR path. `inspect` only reads; `enrich` reads one JAR and writes a
new one. Each prints to the terminal and sets an **exit code** (see [§6](#6-reference)) so
you can use them in scripts.

---

## 4. Tutorial

### Step 1 — Inspect a bundle JAR

```bash
java -jar KSLBundleTools/build/libs/kslpkg.jar \
     inspect KSLExamples/build/libs/KSLExamples-1.0-SNAPSHOT.jar
```

The tool lists every bundle and its models. Abridged output:

```text
JAR: .../KSLExamples-1.0-SNAPSHOT.jar
Discovery: ServiceLoader (META-INF/services/ksl.app.bundle.KSLModelBundle)
Bundles: 4

Bundle: ksl.examples.mm1
  Display name : M/M/1 Queue Example
  Description  : Single-server M/M/1 queue (GIGcQueue) with one controllable factor (numServers).
  Version      : 1.0.0
  KSL API      : 1.2
  Models       : 1
    - MM1 (M/M/1 Queue)
        Apps         : SINGLE, SCENARIO, EXPERIMENT, SIMOPT
        Has in-JAR descriptor : no

Bundle: ksl.examples.lk-inventory
  Display name : LK (s,S) Inventory Model
  Models       : 1
    - LKInventory (LK (s,S) Inventory)
        Apps         : SINGLE, SCENARIO, EXPERIMENT, SIMOPT
        Has in-JAR descriptor : no

Bundle: edu.uark.ksl.book-examples
  Display name : KSL Book Examples
  Models       : 16
        ...
```

**How to read it.** Each **bundle** has an id, display name, and one or more **models**.
For each model you see which **apps** it targets and whether it already carries an
in-JAR **descriptor** (`Has in-JAR descriptor: no` means `enrich` hasn't been run yet).
This example JAR carries four bundles, including the **book-examples** bundle with 16
textbook models.

### Step 2 — Enrich the JAR

`enrich` builds each model once, captures its descriptor (its inputs and outputs), and
writes a copy of the JAR with the descriptors embedded:

```bash
java -jar KSLBundleTools/build/libs/kslpkg.jar \
     enrich KSLExamples/build/libs/KSLExamples-1.0-SNAPSHOT.jar \
     -o KSLExamples-enriched.jar --force
```

```text
Wrote KSLExamples-enriched.jar
  Embedded 20 descriptor entries:
    META-INF/ksl/models/MM1/descriptor.json
    META-INF/ksl/models/LKInventory/descriptor.json
    META-INF/ksl/models/WalkInHealthClinic/descriptor.json
    ... (20 total)
```

### Step 3 — Confirm the enrichment

Inspect the new JAR — the descriptor flag flips to **yes**:

```bash
java -jar KSLBundleTools/build/libs/kslpkg.jar inspect KSLExamples-enriched.jar \
     | grep "Has in-JAR descriptor"
```

```text
        Has in-JAR descriptor : yes
        Has in-JAR descriptor : yes
        Has in-JAR descriptor : yes
        ...
```

Now any consumer can read each model's input/output surface straight from the JAR without
instantiating a Kotlin class — useful for tooling, catalogs, and future hosted runtimes.

---

## 5. Common tasks

| Task | Command |
|---|---|
| See what's in a JAR | `kslpkg inspect <jar>` |
| Enrich to a chosen path | `kslpkg enrich <in.jar> -o <out.jar>` |
| Overwrite an existing output | add `--force` |
| Check the tool version | `kslpkg --version` |
| Wire enrich into a Gradle build | see the `JavaExec` pattern in `KSLBundleTools/README.md` |

---

## 6. Reference

### Commands

- **`inspect <jar>`** — summarize bundles/models. A JAR with no bundles is not an error
  (it reports that and exits 0). Unannotated JARs fall back to a legacy reflective scan.
- **`enrich <input.jar> [-o <output.jar>] [--force]`** — embed `ModelDescriptor` JSON. The
  default output is `<input-stem>-enriched.jar` next to the input. Re-enriching is
  idempotent (one descriptor per model).

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Ran to completion as intended. |
| `1` | User-input error: bad arguments, missing file, output collision, or a JAR with no bundles. |
| `2` | Internal failure: a model failed to build during descriptor extraction, or an I/O write failed. |

Distinguishing `1` from `2` lets scripts treat "bad input" and "tool broke" differently.

---

## 7. Troubleshooting & gotchas

| Symptom | Cause | Fix |
|---|---|---|
| `inspect` says "no bundles" | The JAR doesn't register a `KSLModelBundle` service. | Use a real bundle JAR (e.g. the KSLExamples jar), or check the bundle's `META-INF/services`. |
| `enrich` refuses to write | The output file exists. | Add `--force`, or choose a different `-o` path. |
| `enrich` exits with code 2 | A model threw while building. | Fix the model's `build(...)`; only annotated bundles can be enriched. |
| `java: command not found` | Java isn't on your `PATH`. | Install/select JDK 21 and re-open the terminal. |

---

## 8. See also

- `KSLBundleTools/README.md` — the full tool reference, Gradle/IntelliJ wiring, and the
  `ksl.app.bundle` SPI it serves.
- [Common UI & concepts](common-ui.md) — how the desktop apps consume bundles.
- The app guides — [Single](single.md), [Scenario](scenario.md), [Experiment](experiment.md), [Simopt](simopt.md).

---

<sub>The transcripts above are real output from `kslpkg` run against the KSLExamples bundle
JAR (lightly abridged for length).</sub>
