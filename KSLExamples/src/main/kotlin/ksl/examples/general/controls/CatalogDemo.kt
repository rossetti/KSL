package ksl.examples.general.controls

import kotlinx.serialization.json.Json
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.simulation.ElementCatalogScope
import ksl.simulation.Model
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelElement
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.printText

/**
 * Demonstrates the nominated input/output catalog: per-element declaration via
 * [ModelElement.specifyCatalog] that rolls up the model-element hierarchy, and
 * model-assembly curation via [Model.curateCatalog] (add / override / prune).
 *
 * Two model elements are defined here for the roll-up and flooding parts, because
 * those require an element that overrides `specifyCatalog`.  The post-construction
 * parts use the KSL book model [DriveThroughPharmacyWithQ] **unchanged** — showing
 * how a catalog is attached to a model the analyst does not want to (or cannot) edit.
 *
 * Run `main` to print the whole walk-through as a text report.
 */

/**
 * A small reusable element that nominates its own salient input and output.
 * Reused many times, these flood the catalog — the situation the curation API exists
 * to manage.
 */
class Workstation(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0, upperBound = 20.0)
    var numMachines: Int = 1

    private val myUtilization = TWResponse(this, "$name:Utilization")
    val utilization: ResponseCIfc get() = myUtilization

    override fun specifyCatalog(catalog: ElementCatalogScope) = with(catalog) {
        input(this@Workstation, Workstation::numMachines) { displayName = "$name machines"; unit = "machines" }
        output(myUtilization) { displayName = "$name utilization" }
    }
}

/** Holds several [Workstation]s — a subtree the curation API can prune as a unit. */
class ProductionLine(parent: ModelElement, name: String, numStations: Int) : ModelElement(parent, name) {
    val stations: List<Workstation> = (1..numStations).map { Workstation(this, "$name-WS$it") }
    val bottleneck: Workstation get() = stations.first()
}

/** Nominates one valid and one invalid output — to show lenient (warn + skip) roll-up. */
class MisbehavingElement(parent: ModelElement, name: String) : ModelElement(parent, name) {
    private val myGood = Response(this, "$name:Good")
    override fun specifyCatalog(catalog: ElementCatalogScope) {
        catalog.output(myGood)               // valid
        catalog.output("DoesNotExist")       // invalid — recorded in catalogIssues(), skipped, not thrown
    }
}

/** Renders a [ModelCatalog] as input and output tables within the report. */
private fun ReportBuilder.catalogTables(caption: String, catalog: ModelCatalog?) {
    if (catalog == null || catalog.isEmpty) {
        paragraph("$caption: (empty — nothing nominated)")
        return
    }
    if (catalog.nominatedInputs.isNotEmpty()) {
        dataTable(
            headers = listOf("Kind", "Key", "Display Name", "Unit"),
            rows = catalog.nominatedInputs.map {
                listOf(it.kind.name, it.key, it.displayName ?: "", it.unit ?: "")
            },
            caption = "$caption — inputs"
        )
    }
    if (catalog.nominatedOutputs.isNotEmpty()) {
        dataTable(
            headers = listOf("Name", "Display Name", "Unit"),
            rows = catalog.nominatedOutputs.map {
                listOf(it.name, it.displayName ?: "", it.unit ?: "")
            },
            caption = "$caption — outputs"
        )
    }
}

fun main() {
    val prettyJson = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

    // One pharmacy model, carried across sections 3–6 so its catalog evolves.
    val pharmacyModel = Model("DriveThroughPharmacy", autoCSVReports = false)
    val pharmacy = DriveThroughPharmacyWithQ(pharmacyModel, numServers = 2, name = "Pharmacy")

    val doc = report("Nominated Input/Output Catalog — Demonstration") {

        section("1. Element roll-up and the flooding problem") {
            paragraph(
                "Each Workstation overrides specifyCatalog to nominate its own capacity (an input) and " +
                    "utilization (an output). A ProductionLine of six of them rolls those nominations up the " +
                    "element hierarchy into the model's catalog — six inputs and six outputs. Reuse alone has " +
                    "recreated the 'too many to choose from' problem the catalog is meant to relieve."
            )
            val m = Model("FloodingDemo", autoCSVReports = false)
            ProductionLine(m, "Line", 6)
            catalogTables("Rolled-up catalog (flooded)", m.modelCatalog)
        }

        section("2. Curating the flood with curateCatalog") {
            paragraph(
                "A model assembler prunes the flood from the outside — no element classes are edited. Here we " +
                    "clear the element-declared nominations and keep only the bottleneck. (denominateSubtree(line) " +
                    "would instead drop just that line's contributions, leaving any other element's nominations intact.)"
            )
            val m = Model("PrunedDemo", autoCSVReports = false)
            val line = ProductionLine(m, "Line", 6)
            m.curateCatalog {
                clearElementNominations()
                input(line.bottleneck, Workstation::numMachines) { displayName = "Bottleneck machines"; unit = "machines" }
                output(line.bottleneck.utilization) { displayName = "Bottleneck utilization" }
            }
            catalogTables("Curated catalog", m.modelCatalog)
        }

        section("3. Nominating an unedited book model from the outside") {
            paragraph(
                "DriveThroughPharmacyWithQ (KSL book, chapter 4) is used exactly as shipped — no specifyCatalog " +
                    "override, no edits. curateCatalog nominates its inputs and outputs by object reference. Note that " +
                    "serviceRV is auto-named, yet rvParameter(pharmacy.serviceRV, \"mean\") needs no knowledge of that " +
                    "generated name — the object supplies it."
            )
            pharmacyModel.curateCatalog {
                input(pharmacy, DriveThroughPharmacyWithQ::numPharmacists) { displayName = "Number of Pharmacists"; unit = "servers" }
                rvParameter(pharmacy.serviceRV, "mean") { displayName = "Mean Service Time"; unit = "min" }
                output(pharmacy.systemTime) { displayName = "Avg Time in System"; unit = "min" }
                output(pharmacy.numInSystem) { displayName = "Avg Number in System" }
                output(pharmacy.numCustomersServed) { displayName = "Number Served" }
            }
            catalogTables("Pharmacy catalog (nominated post-construction)", pharmacyModel.modelCatalog)
        }

        section("4. Override and validation feedback") {
            paragraph(
                "Re-nominating an item overrides its metadata — model-level curation wins over an element's roll-up. " +
                    "Invalid element-level nominations are skipped and surfaced through catalogIssues() rather than " +
                    "thrown, so a buggy reusable element cannot crash a consuming model; an invalid model-level " +
                    "nomination throws when the catalog is assembled."
            )
            pharmacyModel.curateCatalog {
                output(pharmacy.systemTime) { displayName = "Mean Sojourn Time"; unit = "min" }
            }
            val relabelled = pharmacyModel.modelCatalog?.nominatedOutputs?.firstOrNull { it.name == "System Time" }?.displayName
            paragraph("After override, the 'System Time' output is labelled: \"$relabelled\".")

            val issuesModel = Model("IssuesDemo", autoCSVReports = false)
            MisbehavingElement(issuesModel, "Bad")
            issuesModel.modelCatalog   // assembles without throwing despite the bad nomination
            paragraph("Element-level issues (warn + skip): " + issuesModel.catalogIssues().joinToString("; "))

            val throwModel = Model("ThrowDemo", autoCSVReports = false)
            DriveThroughPharmacyWithQ(throwModel, name = "P")
            throwModel.curateCatalog { output("NoSuchResponse") }
            val message = try {
                throwModel.modelCatalog
                "(no error)"
            } catch (e: IllegalArgumentException) {
                e.message ?: "(error)"
            }
            paragraph("An invalid model-level nomination throws at assembly: $message")
        }

        section("5. The catalog inside the ModelDescriptor (what applications consume)") {
            paragraph(
                "The catalog is a field of ModelDescriptor and serializes with it; a consumer (GUI, MCP tool, " +
                    "CLI) reads it to decide which inputs and outputs to surface first. Below is the catalog portion " +
                    "of pharmacyModel.modelDescriptor()."
            )
            val catalog = pharmacyModel.modelDescriptor().catalog
            text(prettyJson.encodeToString(ModelCatalog.serializer(), catalog!!))
        }

        section("6. Focusing results on the nominated outputs") {
            paragraph(
                "Finally, running the pharmacy and reporting across-replication statistics for the nominated " +
                    "outputs only — exactly the subset an application would feature, rather than every response in " +
                    "the model."
            )
            pharmacyModel.numberOfReplications = 20
            pharmacyModel.lengthOfReplication = 2000.0
            pharmacyModel.lengthOfReplicationWarmUp = 500.0
            pharmacyModel.simulate()

            val catalog = pharmacyModel.modelCatalog!!
            val stats = catalog.nominatedOutputs.mapNotNull { out ->
                pharmacyModel.response(out.name)?.acrossReplicationStatistic
                    ?: pharmacyModel.counter(out.name)?.acrossReplicationStatistic
            }
            statTable(stats, caption = "Across-replication results for the nominated outputs", confidenceLevel = 0.95)
        }
    }

    doc.printText()
    // doc.showInBrowser()   // uncomment to open the same report as HTML
}
