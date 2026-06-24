# kslpkg — KSL bundle authoring tool

`kslpkg` is a small command-line tool for working with **KSL model bundles**:
JAR files that ship one or more compiled KSL models in a form the KSL apps can
discover and load (`ksl.app.bundle.KSLModelBundle`). Bundle JARs are the unit of
model distribution in KSL — consumed today by the reference Swing apps and, by
design, by any future hosted runtime (REST/gRPC service, MCP server for agent
tools, CLI scripting host).

The tool ships two commands:

- **`inspect`** — print a human-readable summary of the bundles, models, and
  capabilities declared in a JAR.
- **`assemble`** — turn a plain **builders JAR** (a JAR whose only required
  content is one or more named `ksl.simulation.ModelBuilderIfc` classes) into a
  self-describing **bundle JAR**: a `bundle.toml` manifest plus, per model, an
  embedded `ksl.simulation.ModelDescriptor` (and `catalog.toml` when present).
  The model author writes only a `ModelBuilderIfc` — no hand-written
  `KSLModelBundle` class and no `META-INF/services` registration are required;
  the result loads at runtime as a `ManifestBackedBundle`. Each model is built
  **once** at assembly time so consumers can read its input/output surface from
  the JAR without instantiating any Kotlin class.

Per-model authoring (curating a catalog, tuning each model's supported apps) is
the job of the **Bundle Workbench** desktop app, which drives the same headless
authoring core (`ksl.app.bundle.BundleAuthoringSession`) that `assemble` does.

---

## Building the tool

```bash
./gradlew :KSLBundleTools:shadowJar
```

Produces a self-contained fat JAR at:

```
KSLBundleTools/build/libs/kslpkg.jar
```

---

## Usage

### `inspect`

```bash
java -jar kslpkg.jar inspect <jar>
```

Prints a structured summary of every `KSLModelBundle` declared in the JAR.
Discovery prefers a `bundle.toml` manifest (a manifest-backed bundle) and falls
back to a `META-INF/services/ksl.app.bundle.KSLModelBundle` registration; the
`Discovery:` line reports which form was found. A JAR with no bundles is not an
error: the command prints a clear message and exits 0.

**Example output (a manifest bundle):**

```
JAR: /path/to/mymodels-bundle.jar
Discovery: manifest (META-INF/ksl/bundle.toml)
Bundles: 1

Bundle: edu.uark.examples.mm1
  Display name : M/M/1 Queue Example
  Description  : Single-server M/M/1 queue with one controllable factor (numServers).
  Version      : 1.0.0
  KSL API      : 1.2
  Source JAR   : /path/to/mymodels-bundle.jar
  Models       : 1
    - MM1 (M/M/1 Queue)
        Description  : A single-server M/M/1 queue with exponential interarrivals and service.
        Apps         : SINGLE, SCENARIO, EXPERIMENT, SIMOPT
        Has in-JAR descriptor : yes
  Optional metadata:
    Author    : (unset)
    Homepage  : (unset)
    License   : (unset)
    Tags      : (none)
```

The **`Has in-JAR descriptor`** line tells you whether the bundle embeds each
model's descriptor (an `assemble`d bundle always does) — useful when verifying a
build pipeline.

### `assemble`

```bash
java -jar kslpkg.jar assemble <builders.jar> --id <bundleId> [options]
```

Discovers the `ModelBuilderIfc` implementations in `<builders.jar>`, builds each
model once to capture its descriptor, and writes a new bundle JAR. `--id` is
required (it is the bundle's globally-unique identifier); the rest of the bundle
identity is optional:

| Flag | Meaning | Default |
|------|---------|---------|
| `--id <bundleId>` | globally-unique bundle id (required) | — |
| `--name <text>` | display name | the builders-JAR stem |
| `--description <text>` | short description | empty |
| `--version <v>` | bundle content version | `1.0.0` |
| `--author` / `--homepage` / `--license` | optional metadata | unset |
| `--tag <t>` | a free-form tag (repeatable) | none |
| `-o <path>` | output JAR | `<builders-stem>-bundle.jar` beside the input |
| `--force` | overwrite an existing output | off |

Per-model metadata uses sensible defaults — `modelId` derived from the builder
class name, `supportedApps` = SINGLE/SCENARIO/EXPERIMENT. The input builders JAR
is never modified. The draft is validated before the bundle is written: if
validation reports an error (e.g. a malformed `--id`) the bundle is not written
and the command exits 1; warnings are printed but do not block.

**Example:**

```
$ java -jar kslpkg.jar assemble mymodels.jar --id edu.uark.examples.mm1 --name "M/M/1"
Assembled edu.uark.examples.mm1 → mymodels-bundle.jar
  Models (1):
    - MM1 (MM1) ← edu.uark.examples.mm1.MM1Builder
  Validation: clean (0 errors, 0 warnings)
```

The command exits non-zero if:
- the input file is missing or not a regular file (1),
- `--id` is missing (1),
- the builders JAR declares no `ModelBuilderIfc` implementations (1),
- the output exists and `--force` was not supplied (1),
- the assembled bundle fails validation with an error (1),
- a model's `builder().build(...)` throws, or an I/O write fails (2).

---

## Wiring `assemble` into a bundle author's Gradle build

`kslpkg` is not published as a Gradle plugin. Bundle authors who want `assemble`
to run as part of their build add a `JavaExec` task by hand:

```kotlin
// In your bundle project's build.gradle.kts. Assumes kslpkg.jar is available at
// a known path (vendored under tools/, downloaded by a separate task, etc.).

tasks.register<JavaExec>("assembleBundle") {
    group = "ksl bundle"
    description = "Assemble a bundle JAR from this project's ModelBuilderIfc classes."

    val buildersJar = tasks.named<Jar>("jar")
    dependsOn(buildersJar)

    classpath = files("tools/kslpkg.jar")            // <-- adjust
    mainClass.set("ksl.bundle.tools.MainKt")
    inputs.file(buildersJar.flatMap { it.archiveFile })

    doFirst {
        val inputJar = buildersJar.get().archiveFile.get().asFile.absolutePath
        args = listOf("assemble", inputJar, "--id", "edu.example.my-bundle", "--force")
    }
}
```

Run it explicitly:

```bash
./gradlew :your-bundle:assembleBundle
```

---

## Wiring `assemble` as an IntelliJ External Tool

Settings → Tools → External Tools → New:

- **Name:** `kslpkg assemble`
- **Program:** path to your `java` (`$JDKPath$/bin/java` or similar)
- **Arguments:**
  `-jar /path/to/kslpkg.jar assemble "$FilePath$" --id edu.example.my-bundle --force`
- **Working directory:** `$ProjectFileDir$`

With the input builders JAR selected in the project view, invoke the tool from
the right-click context menu.

---

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | The command ran to completion as intended. |
| `1`  | User-input error: bad arguments, missing file, missing `--id`, output collision, a builders JAR with no models, or a bundle that fails validation. |
| `2`  | Internal failure: a model failed to build during descriptor extraction, or an I/O write failed. |

Distinguishing 1 from 2 lets scripts treat "bad input" and "tool broke"
differently.

---

## Related KSL surfaces

- **`ksl.app.bundle`** (in `KSLApp`) — the bundle SPI, authoring core, and loader:
  `KSLModelBundle`, `KSLBundledModel`, `KSLAppKind`, `ManifestBackedBundle`,
  `BundleAuthoringSession`, `BundleAssembler`, `BuilderDiscovery`, `BundleLayout`,
  `BundleLoader`, `LoadedBundle`, `BundleDescriptorCache`.
- **`ksl.simulation.ModelDescriptor`** — the serialized model metadata that
  `assemble` embeds.
- **`KSLAppSession`** — the GUI-agnostic interaction layer that consumes models
  loaded through the bundle SPI.
