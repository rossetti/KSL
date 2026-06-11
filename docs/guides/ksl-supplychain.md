# Using `ksl.modeling.supplychain`

A task-oriented usage guide. For each common task, the smallest amount
of code that does it, and the gotchas that matter in practice.
Reference detail (parameter lists, every overload) is on the Dokka API
pages; this guide gets you productive.

> **Status: experimental.** `ksl.modeling.supplychain` is released as
> experimental. Its public API may change in future releases without
> notice. Pin your KSL version if you build models against it for
> production use.

## 1. What this package is for

`ksl.modeling.supplychain` is a **domain modeling layer** for
multi-echelon supply-chain networks: items, inventories with
replenishment policies, customer demand, transport with optional
shipment formation, cross-docks, and cost accounting — all running on
top of KSL's discrete-event substrate as a single `Model`.

You should reach for it when the thing you're modeling has named
domain primitives — *warehouse*, *retailer*, *cross-dock*, *reorder
point*, *lead time*, *load* — rather than abstract entities flowing
through stations. The package gives you those primitives, an
authoring DSL, and a structured results report so the output of a
multi-echelon run is something you can read at a glance instead of a
flat half-width dump.

### How it differs from its KSL neighbors

- `ksl.utilities.distributions` and `ksl.utilities.random` produce
  **values** drawn from a distribution. This package consumes those
  values (lead times, inter-arrivals) but is not itself a value-
  generator; it is a system the values flow through.
- `ksl.modeling.entity` gives you process-view entities and resources.
  Use it when the natural language of your model is *jobs queueing for
  servers*. Use `ksl.modeling.supplychain` when the natural language is
  *stock, orders, shipments, and lead time*.
- `ksl.modeling.variable` (`Response`, `Counter`, `TWResponse`) is
  what the package produces. Every network metric — on-hand inventory,
  fill rate, cost-by-line — is one of those types, so all the standard
  reporting tools apply.
- `ksl.utilities.io.report` powers the structured results report in
  §4; you can also compose supply-chain sections into your own larger
  report.

### Three authoring paths, one runtime

```
Kotlin DSL    supplyChain { … }   ─┐
TOML / JSON   fromToml / fromJson  ├─►  NetworkSpec ──►  SupplyChainBuilder.build(model, spec) ──►  MultiEchelonNetwork
programmatic  MultiEchelonNetwork ─┘                                                              (lives in a Model;
                                                                                                    simulate as usual)
```

`NetworkSpec` is a serializable canonical description; the DSL and
loaders all produce it. `SupplyChainBuilder.build` is the single
consumer that instantiates a running network. You can also build the
network programmatically against `MultiEchelonNetwork` directly — the
authoring paths are interchangeable; they all produce the same
runtime.

---

## 2. The mental model

### The network is a tree

Every node has exactly one supplier. The root is a built-in *external
supplier* representing the world outside the model; everything else
attaches via a `parent` reference. Multi-sourcing (one node served by
several suppliers) is out of scope in v1.

### Two node kinds: IHPs and cross-docks

An **inventory holding point** (IHP) holds stock for one or more item
types and runs a replenishment policy on each. A **cross-dock** holds
no inventory; it routes demand and forwards shipments. The validator
rejects an inventory attached to a cross-dock.

### Demand and replenishment are the same flow primitive

A `Demand` walks the tree both ways. A customer-arrival demand walks
*down* (from a generator to an IHP, which fills it). When that IHP's
stock drops to its reorder point, the policy creates a replenishment
*demand* and walks it *up* (to the supplier). The state machine is
identical; only the direction differs. This is why "demand" is the
package's central noun.

### Transport is a network-wide strategy

Pick one of three at construction time:

- `SharedCarrier` — one no-delay carrier for every leg. Transport is
  instantaneous; cleanest for "I only care about inventory dynamics".
- `PerIHPTimeBased` — each node owns its own time-based carrier with
  per-edge transport times. **Shipment formation requires this
  strategy**, because per-edge counters live on the per-node carrier.
- `NetworkTimeBased` — one shared time-based carrier keyed on
  `(supplier, customer)` for every edge. Use when transport is the
  modeling focus and the network is large.

### Costs are observers

A cost formulation is constructed *after* the topology is final.
It walks the network, instantiates one calculator per source
(inventory, backlog, edge, load builder, external supplier), each
calculator attaches an observer to its source, and at each
replication's end the formulation rolls up the calculators into a
**(tier × line) grid** of responses plus a grand total. The network
simulates with or without one attached; you can attach several to one
network for comparative studies in a single run.

---

## 3. Quick start

A two-tier network — one warehouse serving two retailers — authored
with the DSL, simulated, and reported. Drop this into a `main`,
adjust the imports for your project, and it runs:

```kotlin
import ksl.modeling.supplychain.report.resultsReport
import ksl.modeling.supplychain.spec.CostParamsSpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.constant
import ksl.modeling.supplychain.spec.exponential
import ksl.modeling.supplychain.spec.supplyChain
import ksl.simulation.Model
import ksl.utilities.io.report.toMarkdown

fun main() {
    val spec = supplyChain("SmallNet") {
        transportStrategy = perIHPTimeBased

        val widget = item("Widget", exponential(mean = 1.0, stream = 1), unitCost = 10.0)

        holdingPoint("Warehouse") {
            attachedToExternalSupplier(transportTime = constant(3.0))
            inventory(widget) { sQ(s = 4, Q = 20, initialOnHand = 30) }

            tier(count = 2, namePrefix = "R", transportTime = constant(1.0)) {
                inventory(widget) { sS(s = 2, S = 8, initialOnHand = 10) }
                demand(widget, exponential(mean = 2.0, stream = autoStream()))
            }
        }
        defaultCost(params = CostParamsSpec(carryingRate = 0.10))
    }

    val m = Model("SmallNet")
    val result = SupplyChainBuilder.build(m, spec)

    m.numberOfReplications = 10
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()

    println(result.network.resultsReport().toMarkdown())
}
```

What you get: a compact Markdown report with the network topology,
the cost matrix by tier × line with a 95% half-width, and per-IHP
fill-rate / on-hand / wait-time numbers. The full half-width report
is still available via `m.simulationReporter.printHalfWidthSummaryReport()`
when you want every response.

---

## 4. How do I…?

### ...author a network — three ways

You'll likely use the DSL most. The other two paths are when (a) you
already have data in TOML/JSON, or (b) you want fine-grained
construction-time control.

**DSL.** Visual nesting lowers to a flat `NetworkSpec` with `parent`
inferred from the enclosing scope:

```kotlin
val spec = supplyChain("Net") {
    val widget = item("Widget", exponential(1.0, stream = 1))
    holdingPoint("Warehouse") {
        attachedToExternalSupplier(constant(3.0))
        inventory(widget) { sQ(s = 4, Q = 20, initialOnHand = 30) }
        holdingPoint("R1") {
            transportTimeFromParent = constant(1.0)
            inventory(widget) { sS(s = 2, S = 8, initialOnHand = 10) }
            demand(widget, exponential(2.0, stream = autoStream()))
        }
    }
}
```

**Load from a file.** Authored TOML/JSON is the friendliest
hand-editable form; both formats are lossless and the same DTOs drive
them:

```kotlin
val spec1: NetworkSpec = NetworkSpec.fromToml(tomlText)
val spec2: NetworkSpec = NetworkSpec.fromJson(jsonText)
val m = Model("FromFile")
SupplyChainBuilder.build(m, spec1)
```

You can also write a spec back out (e.g. to capture a DSL-authored
network for review):

```kotlin
val toml: String = spec.toToml()
val json: String = spec.toJson()
```

**Programmatic.** Build directly against `MultiEchelonNetwork` when
you want the most explicit construction order:

```kotlin
val m = Model("Programmatic")
val sc = SupplyChainModel(m, name = "SC")
val net = MultiEchelonNetwork(
    sc, name = "Net",
    transportStrategy = TransportStrategy.PerIHPTimeBased,
)
val widget = net.addItemType("Widget", ExponentialRV(mean = 1.0, streamNum = 1))
widget.unitCost = 10.0

val warehouse = net.addInventoryHoldingPoint("Warehouse")
warehouse.addReorderPointReorderQuantityInventory(widget, 4, 20, 30)
net.attachIHPToExternalSupplier(warehouse, ConstantRV(3.0))

val r1 = net.addInventoryHoldingPoint("R1")
r1.addReorderPointOrderUpToLevelInventory(widget, 2, 8, 10)
net.attachIHPToSupplier(warehouse, r1, ConstantRV(1.0))
net.attachDemandGeneratorToIHP(r1, widget, ExponentialRV(2.0, streamNum = 11))
```

All three paths produce a `MultiEchelonNetwork` that simulates
identically when the inputs match. The spec-built network is
bit-identical to the equivalent hand-coded one — explicit streams +
same structure ⇒ same sample path.

### ...choose an inventory policy

Three policies ship in v1: `(s, Q)`, `(s, S)`, and periodic-review
`(s, S)`. The DSL block names them after their parameters:

```kotlin
inventory(widget) { sQ(s = 4, Q = 20, initialOnHand = 30) }         // (s, Q)
inventory(widget) { sS(s = 2, S = 8, initialOnHand = 10) }           // (s, S)
inventory(widget) {                                                  // periodic (s, S)
    sSPeriodic(s = 2, S = 8, reviewInterval = constant(5.0), initialOnHand = 10)
}
```

Programmatic equivalents on an IHP are
`addReorderPointReorderQuantityInventory(item, s, Q, initialOnHand)`
and `addReorderPointOrderUpToLevelInventory(item, s, S, initialOnHand)`.
The periodic policy's review interval must be a constant in v1.

### ...add a cross-dock between a supplier and a customer

A cross-dock has no inventory; it forwards. Nest one with the
`crossDock` builder:

```kotlin
supplyChain("WithCD") {
    val w = item("Widget", exponential(1.0, stream = 1))
    holdingPoint("Warehouse") {
        attachedToExternalSupplier()
        inventory(w) { sQ(s = 5, Q = 20, initialOnHand = 30) }
        crossDock("XD") {
            holdingPoint("R") {
                inventory(w) { sS(s = 3, S = 10, initialOnHand = 10) }
                demand(w, exponential(2.0, stream = autoStream()))
            }
        }
    }
}
```

Programmatic equivalent: `net.addInventoryCrossDock("XD")` and
`net.attachToSupplier(warehouse, xd, …)`. The validator rejects any
inventory attached to a `NodeType.CD` node.

### ...switch the transport strategy

The DSL exposes all three as scope properties:

```kotlin
supplyChain("StrategyDemo") {
    transportStrategy = sharedCarrier        // default, no-delay
    // transportStrategy = perIHPTimeBased   // per-edge times; required for shipment formation
    // transportStrategy = networkTimeBased  // one shared time-based carrier
    // …
}
```

The values themselves are
`TransportStrategySpec.SharedCarrier` / `.PerIHPTimeBased` /
`.NetworkTimeBased`; the runtime types in
`ksl.modeling.supplychain.network.TransportStrategy` are the same
shape. Under `SharedCarrier`, per-edge counters do not exist, so
flow-line cost rows (Loading, Shipping, Unloading) report 0 by
design.

### ...bundle outbound shipments into loads

Shipment formation is the *carrier* gathering multiple demands into
one load before dispatch. Three forming options ship: count-based,
weight-based, cube-based. Enable formation on the supplier, then
attach an option to each customer edge:

```kotlin
supplyChain("Formation") {
    transportStrategy = perIHPTimeBased       // required for formation
    val w = item("Widget", exponential(1.0, stream = 1), weight = 2.0, cube = 1.0)
    holdingPoint("Warehouse") {
        attachedToExternalSupplier(constant(3.0))
        enableShipmentFormation = true        // turn on the load carrier

        holdingPoint("R-Count") {
            transportTimeFromParent = constant(1.0)
            shipmentFormationFromParent = countFormation(limit = 3)
            inventory(w) { sS(s = 3, S = 12, initialOnHand = 10) }
            demand(w, exponential(0.5, stream = autoStream()))
        }
        holdingPoint("R-Weight") {
            transportTimeFromParent = constant(1.0)
            shipmentFormationFromParent = weightFormation(min = 5.0, max = 20.0)
            // ...
        }
        holdingPoint("R-Cube") {
            transportTimeFromParent = constant(1.0)
            shipmentFormationFromParent = cubeFormation(min = 3.0, max = 12.0)
            // ...
        }
    }
}
```

Loading / Shipping / Unloading flow costs accrue per dispatched load,
not per demand, when formation is enabled. `RULE`-based formation
(custom user predicate) is the v1 escape hatch — author the spec, then
install the rule programmatically after building.

### ...generate customer demand at a node

Stream numbers are explicit at the spec level (reproducibility is
your responsibility). The DSL adds an `autoStream()` allocator that
materializes a unique stream number into the spec:

```kotlin
supplyChain("Demand") {
    val w = item("Widget", exponential(1.0, stream = 1))
    holdingPoint("Warehouse") {
        attachedToExternalSupplier()
        inventory(w) { sQ(s = 5, Q = 20, initialOnHand = 30) }
        holdingPoint("R") {
            inventory(w) { sS(s = 2, S = 8, initialOnHand = 10) }
            demand(w, exponential(mean = 2.0, stream = 11))                       // explicit
            demand(w, exponential(mean = 3.0, stream = autoStream()), name = "DG-extra")
        }
    }
}
```

`exponential` / `constant` / `uniform` / `triangular` / `lognormal`
are top-level helpers — they're in scope inside any DSL block.

### ...add costs and read them

Cost formulations attach to a built network. The builder does this
for you when you include them in the spec; programmatically, construct
the formulation *after* the topology is final (the framework enforces
this with ordering and coverage guards):

```kotlin
val spec = supplyChain("Cost") {
    // ... topology ...
    defaultCost(name = "baseline", params = CostParamsSpec(carryingRate = 0.10))
}
val m = Model("Cost")
val result = SupplyChainBuilder.build(m, spec)
m.numberOfReplications = 5
m.lengthOfReplication = 1000.0
m.simulate()

val cost = result.network.costFormulations.first()
val holding: Double  = cost.byLineResponse(CostLine.Holding)!!
    .acrossReplicationStatistic.average
val ihpTotal: Double = cost.byTierResponse(NodeTier.IHP)!!
    .acrossReplicationStatistic.average
val grand: Double    = cost.totalCostResponse.acrossReplicationStatistic.average
```

The (tier × line) grid is the rollup surface:
`byTierAndLineResponse(tier, line)` for a specific cell,
`byLineResponse(line)` for column totals, `byTierResponse(tier)` for
row totals, `totalCostResponse` for the grand total. Each is a normal
KSL `ResponseCIfc`, so half-widths, observers, and DataFrame export
all work as usual.

### ...vary cost by node

Use `PerNodeIHPCostFormulation` with an `overrides` map keyed by node
name. The DSL exposes it as `perNodeCost`; programmatically it's a
direct constructor:

```kotlin
PerNodeIHPCostFormulation(
    net,
    defaultParams = CostParams(carryingRate = 0.10),
    overrides = mapOf("Warehouse" to CostParams(carryingRate = 0.30)),
    name = "warehouseHeavy",
)
```

Each calculator picks the override of its *owning* node (an
inventory's holder, an outbound edge's supplier, an inbound edge's
customer), so raising the warehouse's `carryingRate` raises the
warehouse's holding cost only; raising a retailer's `unloadingCost`
raises the cost of unloading shipments *into* that retailer only.

### ...run a comparative cost study in one simulation

Attach two (or more) named formulations to the same network. Both
observe the same sample path, so the totals are directly comparable
without a second run:

```kotlin
supplyChain("Compare") {
    // ... topology ...
    defaultCost(name = "standard",     params = CostParamsSpec(carryingRate = 0.10))
    defaultCost(name = "highCarrying", params = CostParamsSpec(carryingRate = 0.30))
}
```

Each formulation's responses are prefixed with its name (`standard:GrandTotal`,
`highCarrying:GrandTotal`, …), and validation requires distinct,
non-null names when more than one formulation is attached.

### ...get a digestible results report

`network.resultsReport()` returns a `ReportNode.Document` (the KSL
report AST). Render to Markdown, HTML, or text via the standard
helpers — same surface every other KSL report uses:

```kotlin
val doc = network.resultsReport(title = "Run 42")
println(doc.toMarkdown())     // Markdown to console
doc.writeHtml()                 // standalone HTML file under model.outputDirectory
// doc.showInBrowser()         // opens it in the default browser
```

Three composable section builders are also exposed if you'd rather
embed supply-chain sections in a larger report:

```kotlin
ksl.utilities.io.report.dsl.report("Multi-scenario") {
    supplyChainOverview(network)
    supplyChainCostSummary(network, confidenceLevel = 0.95)
    supplyChainInventoryPerformance(network)
}
```

The report collapses to only the rows and columns the topology
actually produced — no CD-tier column on a network without
cross-docks, no `LOST_SALE` row if no demand was lost.

### ...read specific responses (the response surface at a glance)

For tests or programmatic checks, each node and formulation exposes
its responses directly:

```kotlin
for (ihp in network.getInventoryHoldingPoints()) {
    val onHand   = ihp.aggregateOnHandInventory.acrossReplicationStatistic.average
    val onOrder  = ihp.aggregateAmountOnOrder.acrossReplicationStatistic.average
    val backOrd  = ihp.aggregateAmountBackOrdered.acrossReplicationStatistic.average
    val fillRate = ihp.aggregateAvgFirstFillRate.acrossReplicationStatistic.average
    val wait     = ihp.aggregateAvgCustomerWaitTime.acrossReplicationStatistic.average
}
val cost = network.costFormulations.first()
cost.byTierAndLineResponse(NodeTier.IHP, CostLine.Holding)!!
```

Per-(item, IHP) responses are reachable through `ihp.getInventory(item)`
when you want finer granularity.

### ...allocate streams predictably with `StreamRange`

For larger networks, reserve a documented block of stream numbers per
subsystem so the spec stays deterministic and non-overlapping:

```kotlin
val leadTimes = StreamRange(base = 1, count = 10)
val demand    = StreamRange(base = 100, count = 50)

supplyChain("Streams", autoStreamBase = 200) {
    val a = item("A", exponential(1.0, stream = leadTimes.next()))
    val b = item("B", exponential(0.5, stream = leadTimes.next()))
    holdingPoint("W") {
        attachedToExternalSupplier()
        inventory(a) { sQ(5, 20, 30) }
        inventory(b) { sQ(5, 20, 30) }
        holdingPoint("R") {
            inventory(a) { sS(2, 8, 10) }
            inventory(b) { sS(2, 8, 10) }
            demand(a, exponential(2.0, stream = demand.next()))
            demand(b, exponential(3.0, stream = demand.next()))
        }
    }
}
```

`autoStream()` inside a DSL block returns numbers from
`autoStreamBase` upward; `StreamRange` is the equivalent outside the
DSL (or for explicit pre-reservation).

### ...validate a spec before building

`SupplyChainBuilder.build` validates first and throws if the spec is
malformed, with every problem listed. If you generate specs
programmatically (or load them from user-supplied files), you can
pre-check:

```kotlin
val errors: List<SpecError> = spec.validate()
if (errors.isNotEmpty()) {
    for (e in errors) println("  - ${e.message}")
    error("network spec has ${errors.size} validation problems")
}
```

The validator catches: cycles, dangling parents, missing items,
cross-docks with inventory, formation under the wrong transport
strategy, duplicate names, multi-formulation naming conflicts, and
more. Errors are accumulated, not first-fail, so you fix data rather
than chase a stack trace.

---

## 5. The key types at a glance

A short tour, grouped by what you reach for. Member-by-member detail
is on the Dokka API site.

**Network top-level**

- `MultiEchelonNetwork` — the runtime network; lives under a
  `SupplyChainModel` which lives under a `Model`.
- `TransportStrategy` (`SharedCarrier`, `PerIHPTimeBased`,
  `NetworkTimeBased`) — pick at construction.
- `ShipmentFormation` + `DemandLoadBuilder.LoadFormingOption` —
  runtime types behind the `*Formation()` DSL helpers.

**Nodes**

- `InventoryHoldingPoint`, `InventoryCrossDock` — concrete node types.
- `NetworkNodeIfc` — the supertype used by the generalised attach API.

**Items, inventories, policies**

- `ItemType` — has `unitCost`, `weight`, `cube`, and the external-
  supplier lead time.
- `Inventory` — per-(IHP, item) container; you create one via the
  IHP's `addReorderPoint…Inventory` factories or the DSL's
  `inventory(item) { … }` block.
- `InventoryPolicyReorderPointReorderQuantity` /
  `…OrderUpToLevel` / `…OrderUpToLevelPeriodic` — the three policy
  implementations.

**Demand surface**

- `DemandGenerator` — the customer-demand source attached at a node.
- `DemandStateId` — the 12-state demand lifecycle (sealed singletons);
  observe via `Demand.addStateChangeListener(...)` if you need
  per-demand hooks.
- `DemandFillerFinderIfc` — the per-demand supplier-selection escape
  hatch (the foundation for v2 multi-sourcing).

**Costs**

- `DefaultMultiEchelonCostFormulation` — uniform parameters
  network-wide.
- `PerNodeIHPCostFormulation` — same, with per-node `CostParams`
  overrides.
- `CostParams` — the parameter bundle (carrying rate, ordering cost,
  loading / shipping / unloading, backorder rate, stockout, lost-sale,
  unit-shortage, ES loading).
- `CostLine`, `NodeTier` — the rollup grid axes; the `all` registry
  on each enumerates every value the framework defines.

**Data layer**

- `NetworkSpec` — the canonical serializable description.
- `SupplyChainBuilder` (object) — the single consumer:
  `build(model, spec): BuildResult`.
- `supplyChain(name) { … }` — the DSL entry point.
- `NetworkSpec.fromToml(text)` / `fromJson(text)` / `toToml()` /
  `toJson()` — the codec extension functions.
- `validate(): List<SpecError>` — pre-build validation.

**Reporting**

- `MultiEchelonNetwork.resultsReport(title, confidenceLevel)` — the
  one-call structured report.
- `supplyChainOverview` / `supplyChainCostSummary` /
  `supplyChainInventoryPerformance` — `ReportBuilder` extensions for
  composing into a larger report.

---

## 6. Gotchas & best practices

- **Construct cost formulations *last*.** The framework guards
  ordering (sources must finish their replication before the
  formulation rolls up) and coverage (no nodes/edges added after
  construction). The DSL/builder does it for you; if you build the
  network programmatically, attach the formulation only after the
  topology is final. Violations fail loud, not silent.
- **Stream numbers are explicit, not auto-magic.** Every stochastic
  `RVSpec` carries a `stream: Int`. Use `autoStream()` inside the DSL
  or a `StreamRange` outside it — but the resulting spec is
  deterministic, and identical streams + identical structure produce
  identical sample paths (bit-identical, not just statistically
  close).
- **A node has exactly one supplier.** v1 is a tree. If you need
  multi-sourcing, treat that as a known limitation; the
  `DemandFillerFinderIfc` primitive is the routing-engine half of it,
  but the network API enforces single-parent.
- **Cross-docks hold no inventory.** Attaching an inventory to a
  `NodeType.CD` node fails validation. If you need a holding cross-
  dock, model it as an IHP with a permissive policy.
- **Shipment formation requires `PerIHPTimeBased`.** The other two
  strategies don't carry per-edge counters that the load carrier
  needs. The validator catches this before the model is built.
- **The structured report is the readable default.** The exhaustive
  `printHalfWidthSummaryReport()` lists *every* response (hundreds, on
  a multi-tier network with formation). Use the structured report for
  routine work; reach for the half-width report when you need every
  response.
- **Validate generated specs.** If a `NetworkSpec` comes from a
  loader or a generator (not directly from the DSL), call `validate()`
  before passing it to the builder. You get the full list of errors,
  not just the first one.

---

## 7. See also

**Related KSL packages**

- `ksl.modeling.entity` — process-view entities and resources; reach
  for it when your model is naturally about jobs and servers rather
  than stock and orders.
- `ksl.modeling.variable` — the `Response` / `Counter` / `TWResponse`
  types that every supply-chain metric is built on.
- `ksl.utilities.random`, `ksl.utilities.distributions` — the source
  of lead times, inter-arrival times, and any other stochastic input
  this package consumes.
- `ksl.utilities.io.report` — the report DSL the structured supply-
  chain report extends.

**Other usage guides** *(in `docs/guides/`)*

- `ksl-modeling.md` — the underlying modeling primitives.
- `ksl-utilities-random.md` — stream and distribution control.
- `ksl-utilities-io.md` — the broader report / I/O surface.
- `ksl-simulation.md` — the `Model` lifecycle this package runs inside.

**Examples** *(under `KSLExamples/src/main/kotlin/ksl/examples/general/supplychain/`)*

| Example | Showcases |
|---|---|
| `MultiEchelonNetworkSSPolicyExample` | hand-coded `(s, S)` warehouse + 5-retailer fan-out, `PerIHPTimeBased` |
| `MultiEchelonNetworkShipperExample` | `NetworkTimeBased` shared carrier |
| `MultiEchelonTimeBasedShippingExample` | per-IHP time-based shipping end-to-end |
| `MultiEchelonNetworkVariantsExample` | A/B comparison: no-delay vs `TransportDelay` |
| `CostFormulationBasicExample` | minimal cost-formulation attach |
| `CostFormulationCustomParamsExample` | tuning `CostParams` |
| `CostFormulationComparativeExample` | two formulations, one run |
| `CostFormulationDrillDownExample` | reading individual cost lines |
| `SupplyChainSpecBuilderExample` | programmatic `NetworkSpec` → build |
| `SupplyChainSpecFileExample` | load a `.toml` → build → run |
| `SupplyChainDslExample` | the DSL with `tierFromTables` |
| `SupplyChainComparativeCostExample` | multi-formulation study from a file |
| `SupplyChainSpecTourExample` | guided tour: author → validate → serialize → run |
| `SupplyChainReportExample` | the structured Markdown / HTML report |

**KSL Book**

For background on replications, warmup, output analysis, and variance
reduction, see [the KSL Book](https://rossetti.github.io/KSLBook/).
