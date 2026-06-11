# Guide: `ksl.controls`

A task-oriented guide to the KSL's **control** system: exposing model parameters
as named, externally-settable values that can be read, written, validated, and
serialized without touching the model classes directly.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** covers the broader
  experimentation context.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from
`KSLExamples/.../book/chapter5/Ch5Example8.kt` and
`KSLExamples/.../general/controls/VanControlsDemo.kt`.

> **Scope.** This guide covers the control-annotation system in the top-level
> `ksl.controls` package. The **`ksl.controls.experiments`** subpackage
> (factorial designs, scenarios, designed experiments) builds on controls and is
> covered in a separate guide.

---

## 1. What this package is for

`ksl.controls` lets you mark selected properties of your model elements as
**controls** — named parameters that external code can discover and set at run
time. This decouples *who runs the model* from *how the model is built*: an
optimization routine, a designed experiment, a batch runner, or a GUI can change
"the number of workers" or "the service rate" by name, without referencing your
classes or recompiling.

The payoff: you annotate a setter once, and the parameter becomes addressable as
a key like `"PWC.numWorkers"`, settable from a `Map`, from JSON, or
programmatically — with bounds-checking and validation handled for you.

### Three families of control

| Family | Annotation | Value type | For... |
|---|---|---|---|
| **Numeric** | `@KSLControl` | `Double` (covers Int, Long, Short, Byte, Boolean too) | Numeric and boolean parameters. |
| **String** | `@KSLStringControl` | `String` | Categorical/text parameters, optionally constrained to allowed values. |
| **JSON** | `@KSLJsonControl` | JSON `String` | Structured parameters (`List`, `Map`) via kotlinx.serialization. |

### How it relates to its neighbors

| Package | Role |
|---|---|
| `ksl.simulation` | Source of controls — `model.controls()` extracts them from the element tree. |
| `ksl.modeling.*` | Where you place the annotated properties (on your `ModelElement`s). |
| `ksl.controls.experiments` | Designed experiments / scenarios that *drive* controls (separate guide). |
| `ksl.utilities.io.report` | Renders control reports (`toReport`, `controlsReport`). |

---

## 2. The mental model

The whole system is one short pipeline:

```
@set:KSLControl on a `var` setter        (1) declare
        │
        ▼
model.controls()  ──▶  Controls           (2) extract  (reflection over the element tree)
        │
        ▼
controls.control("Element.property")      (3) address by key
        │   control.value = ...
        ▼
writes through to the model element property   (4) apply (with bounds/validation)
```

1. **Declare.** Put the annotation on a **property setter** (`@set:KSLControl`)
   of a `var` in your `ModelElement`. Only annotated setters become controls.
2. **Extract.** `model.controls()` reflects over the entire model-element tree
   and returns a `Controls` collection of everything annotated.
3. **Address.** Each control has a **key** of the form
   `"<elementName>.<propertyName>"` (e.g. `"PWC.numWorkers"`,
   `"Van_1.numSeats"`). You look controls up by key.
4. **Apply.** Setting `control.value` writes through to the real property —
   clamped to declared bounds (numeric) or validated against allowed values
   (string), throwing `ControlUpdateException` on an invalid set.

Three things follow from this design:

- **Controls are a view, not a copy.** Writing a control mutates the live model
  element. Extract `controls()` after the tree is built.
- **Everything is a `Double` for numeric controls** — including booleans, by the
  `1.0`/`0.0` convention. Bounds are enforced by clamping.
- **The three families are addressed separately** — numeric via `control(key)`,
  string via `stringControl(key)`, JSON via `jsonControl(key)` — and each has its
  own `size`, key set, map setter, and JSON I/O.

---

## 3. Quick start

Annotate a parameter, then discover and set it through `Controls`.

```kotlin
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.Model
import ksl.simulation.ModelElement

class PalletWorkCenter(parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {

    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
    var numWorkers: Int = 2
        set(value) {
            require(value >= 1) { "The number of workers must be >= 1" }
            field = value
        }
}

fun main() {
    val model = Model("Pallet Model")
    val pwc = PalletWorkCenter(model, name = "PWC")

    val controls = model.controls()                 // extract all controls

    controls.controlKeys().forEach(::println)       // e.g. "PWC.numWorkers"

    val c = controls.control("PWC.numWorkers")!!     // look up by key
    c.value = 3.0                                    // writes through to the property
    println("num workers = ${pwc.numWorkers}")       // 3
}
```

---

## 4. How do I...?

### ...expose a numeric (or boolean) parameter as a control?

Annotate the `var`'s setter with `@set:KSLControl`, giving the `controlType` and
(optionally) bounds, a name, and a comment.

```kotlin
@set:KSLControl(
    controlType = ControlType.INTEGER,
    name        = "numberOfSeats",     // optional; defaults to the property name
    lowerBound  = 1.0,
    upperBound  = 18.0,
    comment     = "0 seats == autonomous?"
)
var numSeats: Int = 3

@set:KSLControl(controlType = ControlType.DOUBLE)
var price: Double = 1.2345

@set:KSLControl(controlType = ControlType.BOOLEAN)
var isStickShift: Boolean = true
```

`ControlType` values: `DOUBLE`, `INTEGER`, `LONG`, `FLOAT`, `SHORT`, `BYTE`,
`BOOLEAN`.

### ...expose a string or structured (JSON) parameter?

```kotlin
// constrained string control
@set:KSLStringControl(allowedValues = ["GASOLINE", "DIESEL", "ELECTRIC", "HYBRID"])
var fuelType: String = "GASOLINE"

// unconstrained string control
@set:KSLStringControl(comment = "Exterior paint colour")
var colour: String = "WHITE"

// JSON controls for List / Map valued properties
@set:KSLJsonControl(comment = "Gross weight per axle in kg")
var axleWeights: List<Double> = listOf(4500.0, 6000.0, 6000.0)
```

### ...discover the controls in a model?

```kotlin
val controls = model.controls()

println("numeric: ${controls.size}, string: ${controls.stringControlSize}, json: ${controls.jsonControlSize}")
controls.controlKeys()        // numeric keys
controls.stringControlKeys()  // string keys
controls.printControls()      // human-readable dump
println(controls.controlDataAsString())
```

### ...read and set a control by key?

```kotlin
// numeric: value is a Double, clamped to declared bounds
val seats = controls.control("Van_1.numSeats")
seats?.value = 100.0          // clamped to upperBound (18)

// boolean: 1.0 == true, 0.0 == false
controls.control("Van_2.isStickShift")?.value = 0.0

// string: validated against allowedValues
val fuel = controls.stringControl("Van_1.fuelType")
fuel?.value = "ELECTRIC"

// json: value is a JSON string
controls.jsonControl("Truck_0.axleWeights")?.value = "[5000.0, 7000.0, 7000.0]"
```

### ...inspect a control's metadata?

Each `ControlIfc` exposes `keyName`, `type`, `lowerBound`, `upperBound`,
`propertyName`, `elementName`, and `comment`, plus helpers:

```kotlin
val c = controls.control("PWC.numWorkers")!!
println("${c.keyName} type=${c.type} [${c.lowerBound}, ${c.upperBound}]")
println(c.withinRange(5.0))      // true if 5.0 is in bounds
println(c.limitToRange(100.0))   // clamps to the bound
```

### ...set many controls at once (Map or JSON)?

```kotlin
// numeric, from a map of key -> Double
controls.setControlsFromMap(mapOf("PWC.numWorkers" to 4.0))

// numeric, from / to JSON
val applied = controls.setControlsFromJson(jsonString)
val json = controls.controlsMapAsJsonString()

// string controls, from a map (invalid entries are skipped)
controls.setStringControlsFromMap(mapOf("Van_0.fuelType" to "DIESEL"))

// json controls, from a map
controls.setJsonControlsFromMap(mapOf("Truck_1.axleWeights" to "[3000.0, 5500.0]"))
```

Bulk setters return the **count of successfully applied** entries; invalid keys
or values are skipped (string/JSON) or clamped (numeric).

### ...snapshot and restore all controls?

Export a full snapshot across all three families, mutate freely, then restore.

```kotlin
val snapshot = controls.exportAllAsJson()   // capture current state
// ... mutate controls / run scenarios ...
val result = controls.importAllFromJson(snapshot)   // restore
// inspect result for success count, validation failures, missing keys
```

### ...handle an invalid control assignment?

Invalid string/JSON sets throw `ControlUpdateException`, which carries the
offending key and attempted value.

```kotlin
import ksl.controls.ControlUpdateException

try {
    controls.stringControl("Van_1.fuelType")?.value = "HYDROGEN"   // not allowed
} catch (e: ControlUpdateException) {
    println("Rejected ${e.attemptedValue} for ${e.controlKey}: ${e.message}")
}
```

### ...produce a report of a model's controls?

The `ksl.utilities.io.report` extensions render controls to HTML/Markdown.

```kotlin
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser

controls.toReport("Model Controls").showInBrowser()
```

---

## 5. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**Annotations (put on `var` setters)**

| Annotation | Declares | Key parameters |
|---|---|---|
| `@KSLControl` | A numeric/boolean control. | `controlType`, `lowerBound`, `upperBound`, `name`, `comment`, `include`. |
| `@KSLStringControl` | A string control. | `allowedValues`, `name`, `comment`, `include`. |
| `@KSLJsonControl` | A JSON-serializable structured control. | `comment`, `include`. |

**Core types**

| Type | Role |
|---|---|
| `Controls` | The extracted collection: `control`/`stringControl`/`jsonControl(key)`, `controlKeys()`, `asMap()`, the `setControlsFrom*` setters, `exportAllAsJson`/`importAllFromJson`, sizes, and dumps. |
| `ControlIfc` / `Control` | A single numeric control: `value` (Double), `keyName`, `type`, bounds, `withinRange`, `limitToRange`. |
| `StringControlIfc` / `StringControl` | A single string control: `value` (String), `allowedValues`. |
| `JsonControlIfc` / `JsonControl` | A single JSON control: `value` (JSON String), `typeHint`. |
| `ControlType` | The numeric type enum (`DOUBLE`, `INTEGER`, `LONG`, `FLOAT`, `SHORT`, `BYTE`, `BOOLEAN`). |
| `ControlData` / `StringControlData` / `JsonControlData` | Serializable snapshots of a control's metadata + value. |
| `ControlUpdateException` | Thrown on an invalid set; carries `controlKey` and `attemptedValue`. |
| `ModelControlsExport` | A serializable export of all of a model's controls. |

---

## 6. Gotchas and best practices

- **Annotate the setter, not the property.** Use `@set:KSLControl` (and the
  string/JSON variants) — the annotations target `PROPERTY_SETTER`. The property
  must be a `var`.

- **Extract `controls()` after the model is built.** Control extraction reflects
  over the current element tree; create all your elements first, then call
  `model.controls()`.

- **Keys are `elementName.propertyName`.** Name your model elements meaningfully
  (e.g. `PalletWorkCenter(model, name = "PWC")`) so control keys are stable and
  readable. Unnamed elements get generated names, making keys fragile.

- **Numeric controls are `Double`, including booleans.** Set a boolean via
  `1.0`/`0.0`. Out-of-bounds numeric values are **clamped**, not rejected — check
  `withinRange` first if you need to detect that.

- **String/JSON invalid sets throw; numeric clamps.** A disallowed string value
  or malformed JSON raises `ControlUpdateException`; catch it (or use the bulk
  map setters, which skip invalid entries and report the applied count).

- **Address the right family.** `control(key)` only finds numeric controls;
  use `stringControl(key)` / `jsonControl(key)` for the others. Each family has
  its own size and key set.

- **Setting a control mutates the live model.** Snapshot with `exportAllAsJson()`
  before experimentation if you need to restore the original configuration.

- **Guard structural setters against running changes.** A common pattern is to
  `require(!model.isRunning)` in the setter so a control can't be changed
  mid-replication.

---

## 7. See also

- **Where controls come from:** `ksl.simulation` (`Model.controls()`) and your
  `ksl.modeling.*` model elements that carry the annotations.
- **What drives controls:** `ksl.controls.experiments` (factorial designs,
  scenarios, designed experiments) — a separate guide.
- **Reporting:** `ksl.utilities.io.report` (`toReport`, `controlsReport`).
- **Runnable examples:** `Ch5Example8.kt` (basic control extraction and setting)
  and `VanControlsDemo.kt` (numeric, string, JSON controls and export/import).
- **Theory and workflow:** the [KSL Book](https://rossetti.github.io/KSLBook/).
