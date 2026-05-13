# kslpkg — KSL bundle authoring tool

`kslpkg` is a small command-line tool for working with **KSL model bundles**:
JAR files that ship one or more compiled KSL models behind a discoverable
service-provider interface (`ksl.app.bundle.KSLModelBundle`). Bundle JARs
are the unit of model distribution in KSL — consumed today by the four
reference Swing apps and, by design, by any future hosted runtime
(REST/gRPC service, MCP server for agent tools, CLI scripting host).

The tool ships two commands:

- **`inspect`** — print a human-readable summary of the bundles, models,
  capabilities, and recipes declared in a JAR.
- **`enrich`** — read a bundle JAR, build each declared model once to
  extract its `ksl.simulation.ModelDescriptor`, and write a copy of the
  JAR with the descriptors embedded under `META-INF/ksl/models/<modelId>/descriptor.json`.
  Consumers can then read each model's input/output surface from the JAR
  without instantiating any Kotlin class.

`kslpkg` is intentionally minimal in Phase 6 of the KSL roadmap. Authoring
helpers (`init`, `convert`, `validate`) are deferred to later phases.

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
Falls back to the legacy reflective scan for unannotated JARs (the same
fallback the runtime loader uses) and tags any synthesized bundle as
`(legacy)`. A JAR with no bundles is not an error: the command prints a
clear message and exits 0.

**Example output:**

```
JAR: /path/to/KSLExamples-1.0-SNAPSHOT.jar
Discovery: ServiceLoader (META-INF/services/ksl.app.bundle.KSLModelBundle)
Bundles: 2

Bundle: ksl.examples.mm1
  Display name : M/M/1 Queue Example
  Description  : Single-server M/M/1 queue (GIGcQueue) with one controllable factor (numServers).
  Version      : 1.0.0
  KSL API      : 1.2
  Source JAR   : /path/to/KSLExamples-1.0-SNAPSHOT.jar
  Models       : 1
    - MM1 (M/M/1 Queue)
        Description  : A single-server M/M/1 queue with exponential interarrivals and service.
        Apps         : SINGLE, SCENARIO, EXPERIMENT, SIMOPT
        Has in-JAR descriptor : no
        Recipes      : (none)
  Optional metadata:
    Author    : (unset)
    Homepage  : (unset)
    License   : (unset)
    Tags      : (none)
```

The **`Has in-JAR descriptor`** line tells you whether `enrich` has
already been applied — useful when verifying a build pipeline.

### `enrich`

```bash
java -jar kslpkg.jar enrich <input.jar> [-o <output.jar>] [--force]
```

- Default output: `<input-stem>-enriched.jar` next to the input.
- `-o <path>` redirects the output to a specific location.
- `--force` allows overwriting an existing output file.

The command exits non-zero if:
- The input file is missing or not a regular file (exit 1).
- The output file already exists and `--force` was not supplied (exit 1).
- The input JAR has no `META-INF/services/ksl.app.bundle.KSLModelBundle`
  registration (exit 1). The legacy reflective fallback used at runtime
  is not consulted here; only annotated bundles can be enriched.
- A bundle's `builder().build(...)` throws while extracting a descriptor
  (exit 2).

Re-enriching is structurally idempotent: pre-existing descriptor entries
in the input are dropped and re-emitted in the appended section, so the
output JAR carries exactly one descriptor per model. **Note** that the
descriptor JSON contents themselves are not byte-stable across runs —
`Model.modelDescriptor()` captures a construction-time marker and an
auto-incrementing experiment id. This is a substrate-level concern,
unrelated to enrich, and is tracked for future work.

---

## Wiring `enrich` into a bundle author's Gradle build

`kslpkg` is not currently published as a Gradle plugin. Bundle authors
who want enrich to run as part of their build add a `JavaExec` task by
hand. The pattern is short:

```kotlin
// In your bundle project's build.gradle.kts.
//
// Assumes kslpkg.jar is available at a known path. Adjust the path
// reference for how you distribute the tool inside your project
// (vendored under tools/, downloaded by a separate task, etc.).

tasks.register<JavaExec>("enrichBundle") {
    group = "ksl bundle"
    description = "Embed ModelDescriptor JSON into a copy of this bundle's JAR."

    val bundleJar = tasks.named<Jar>("jar")
    dependsOn(bundleJar)

    classpath = files("tools/kslpkg.jar")            // <-- adjust
    mainClass.set("ksl.bundle.tools.MainKt")

    inputs.file(bundleJar.flatMap { it.archiveFile })
    inputs.file("tools/kslpkg.jar")

    doFirst {
        val inputJar = bundleJar.get().archiveFile.get().asFile.absolutePath
        args = listOf("enrich", inputJar, "--force")
    }
}
```

Run it explicitly:

```bash
./gradlew :your-bundle:enrichBundle
```

A reference example of this pattern lives in this repository at
`KSLExamples/build.gradle.kts` (`enrichExampleBundle`), producing
`KSLExamples-<version>-enriched.jar`.

---

## Wiring `enrich` as an IntelliJ External Tool

Settings → Tools → External Tools → New:

- **Name:** `kslpkg enrich`
- **Program:** path to your `java` (`$JDKPath$/bin/java` or similar)
- **Arguments:**
  `-jar /path/to/kslpkg.jar enrich "$FilePath$" --force`
- **Working directory:** `$ProjectFileDir$`

With the input JAR selected in the project view, invoke the tool from
the right-click context menu.

---

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | The command ran to completion as intended. |
| `1`  | User-input error: bad arguments, missing file, output collision, JAR with no bundles. |
| `2`  | Internal failure: a model failed to build during descriptor extraction, or an I/O write failed. |

Distinguishing 1 from 2 lets scripts treat "bad input" and "tool broke"
differently.

---

## Related KSL surfaces

- **`ksl.app.bundle`** (in `KSLCore`) — the bundle SPI:
  `KSLModelBundle`, `KSLBundledModel`, `KSLConfigRecipe`, `KSLAppKind`,
  `BundleLayout`, `BundleLoader`, `LoadedBundle`, `BundleDescriptorCache`.
- **`ksl.simulation.ModelDescriptor`** — the serialized model metadata
  that `enrich` embeds.
- **`KSLAppSession`** — the GUI-agnostic interaction layer that consumes
  models loaded through the bundle SPI.

For the strategic context behind the bundle substrate, see
`.claude/plans/Phase6StrategicPlan-v2.md` in this repository.
