package ksl.examples.general.supplychain

import ksl.modeling.supplychain.spec.CostParamsSpec
import ksl.modeling.supplychain.spec.NetworkSpec
import ksl.modeling.supplychain.spec.NodeSpec
import ksl.modeling.supplychain.spec.NodeType
import ksl.modeling.supplychain.spec.PolicySpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.constant
import ksl.modeling.supplychain.spec.countFormation
import ksl.modeling.supplychain.spec.exponential
import ksl.modeling.supplychain.spec.fromToml
import ksl.modeling.supplychain.spec.supplyChain
import ksl.modeling.supplychain.spec.toJson
import ksl.modeling.supplychain.spec.toToml
import ksl.modeling.supplychain.spec.validate
import ksl.simulation.Model

/**
 * A guided tour of the data-driven authoring layer (DSL plan D1–D5),
 * written to let a reader *evaluate the API surface* — its
 * expressiveness, ergonomics, and the experience of moving a network
 * between Kotlin, a file, and a running model.
 *
 * It walks the full user journey and prints the artifact at each step:
 *
 *  1. **Author** a two-item, multi-tier network with the Kotlin DSL —
 *     items, a root DC, a node × item retailer table (`tierFromTables`),
 *     a load-forming "bulk" retailer, auto-allocated streams, and two
 *     cost regimes.
 *  2. **Validate** it — and then validate a deliberately broken copy so
 *     the error-reporting experience is visible.
 *  3. **Serialize** to TOML and JSON (what the user would commit / edit).
 *  4. **Round-trip** TOML → `NetworkSpec` and confirm it is lossless.
 *  5. **Build and run**, reporting both cost regimes side by side.
 *
 * Run via
 * `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.SupplyChainSpecTourExampleKt`
 * or by invoking `main` from an IDE.
 */
fun main() {
    // ----------------------------------------------------------------- 1. author
    // autoStreamBase = 100 reserves the demand streams from 100 up, away
    // from the explicit lead-time streams 1 and 2.
    val spec = supplyChain("AcmeChain", autoStreamBase = 100) {
        transportStrategy = perIHPTimeBased

        val widget = item("Widget", exponential(1.0, stream = 1), unitCost = 12.50, weight = 2.0)
        val gadget = item("Gadget", exponential(1.5, stream = 2), unitCost = 7.25, weight = 1.0)

        holdingPoint("CentralDC") {
            attachedToExternalSupplier(constant(3.0))
            enableShipmentFormation = true // outbound legs can form loads
            inventory(widget) { sQ(s = 6, Q = 30, initialOnHand = 40) }
            inventory(gadget) { sQ(s = 8, Q = 40, initialOnHand = 50) }

            // Three regional retailers expanded from a node × item table:
            // each row is a retailer, each column an item.
            tierFromTables(
                namePrefix = "R",
                items = listOf(widget, gadget),
                policyTable = listOf(
                    listOf(PolicySpec.SS(3, 10), PolicySpec.SS(4, 12)), // R1
                    listOf(PolicySpec.SS(2, 8), PolicySpec.SS(3, 9)),   // R2
                    listOf(PolicySpec.SS(4, 14), PolicySpec.SS(2, 7)),  // R3
                ),
                initialOnHand = 10,
                transportTime = constant(1.0),
                demandTable = listOf(
                    listOf(exponential(2.0, autoStream()), exponential(2.5, autoStream())),
                    listOf(exponential(1.5, autoStream()), exponential(3.0, autoStream())),
                    listOf(exponential(2.2, autoStream()), exponential(1.8, autoStream())),
                ),
            )

            // A high-volume retailer whose inbound leg bundles shipments
            // into loads of three before dispatch.
            holdingPoint("R-Bulk") {
                transportTimeFromParent = constant(1.0)
                shipmentFormationFromParent = countFormation(limit = 3)
                inventory(widget) { sS(s = 5, S = 20, initialOnHand = 15) }
                demand(widget, exponential(0.75, stream = autoStream()))
            }
        }

        // Two cost regimes evaluated in a single run.
        defaultCost(name = "baseline", params = CostParamsSpec(carryingRate = 0.10))
        perNodeCost(
            name = "dcHeavy",
            default = CostParamsSpec(carryingRate = 0.10),
            overrides = mapOf("CentralDC" to CostParamsSpec(carryingRate = 0.35)),
        )
    }

    println("=== 1. authored network ===")
    println("name              : ${spec.name}")
    println("transportStrategy : ${spec.transportStrategy}")
    println("items             : ${spec.items.map { it.name }}")
    println("nodes             : ${spec.nodes.map { "${it.name}(${it.type}, parent=${it.parent})" }}")
    println("demand generators : ${spec.demandGenerators.size}")
    println("cost formulations : ${spec.costFormulations.map { it.name }}")

    // ----------------------------------------------------------------- 2. validate
    println()
    println("=== 2. validation ===")
    val errors = spec.validate()
    println("authored spec valid? ${errors.isEmpty()}")

    // Break a copy two ways at once to show the error experience: a
    // dangling parent and a cross-dock carrying inventory.
    val broken = spec.copy(
        nodes = spec.nodes + listOf(
            NodeSpec("Orphan", NodeType.IHP, parent = "Nowhere"),
            NodeSpec(
                "BadXD", NodeType.CD, parent = "CentralDC",
                inventory = spec.nodes.first { it.name == "R1" }.inventory,
            ),
        ),
    )
    println("broken spec errors (all reported at once):")
    broken.validate().forEach { println("  - ${it.message}") }

    // ----------------------------------------------------------------- 3. serialize
    println()
    println("=== 3a. serialized to TOML (what you'd commit / hand-edit) ===")
    val toml = spec.toToml()
    println(toml)

    println("=== 3b. serialized to JSON (first 600 chars) ===")
    val json = spec.toJson()
    println(json.take(600) + if (json.length > 600) "\n…(${json.length} chars total)…" else "")

    // ----------------------------------------------------------------- 4. round-trip
    println()
    println("=== 4. round-trip TOML -> NetworkSpec ===")
    val reloaded = NetworkSpec.fromToml(toml)
    println("reloaded == original? ${reloaded == spec}")

    // ----------------------------------------------------------------- 5. build & run
    println()
    println("=== 5. build and run ===")
    val m = Model("AcmeChain-Tour")
    val result = SupplyChainBuilder.build(m, reloaded)
    println(
        "built: ${result.network.getInventoryHoldingPoints().size} IHPs, " +
            "${result.network.getInventoryCrossDocks().size} cross-docks, " +
            "${result.network.costFormulations.size} cost formulations",
    )

    m.numberOfReplications = 20
    m.lengthOfReplication = 3650.0
    m.lengthOfReplicationWarmUp = 365.0
    m.simulate()

    // Compare the two cost regimes from the same run.
    val baseline = m.responses.first { it.name == "baseline:GrandTotal" }.acrossReplicationStatistic.average
    val dcHeavy = m.responses.first { it.name == "dcHeavy:GrandTotal" }.acrossReplicationStatistic.average
    println("baseline grand total : %.2f".format(baseline))
    println("dcHeavy  grand total : %.2f  (CentralDC carryingRate 0.10 -> 0.35)".format(dcHeavy))

    println()
    println("=== full half-width summary report ===")
    m.simulationReporter.printHalfWidthSummaryReport()
}
