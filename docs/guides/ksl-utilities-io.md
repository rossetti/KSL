# Guide: `ksl.utilities.io`

A task-oriented guide to input/output in the KSL: managing output directories,
reading and writing files (CSV, Excel, tabular, data frames), databases,
plotting, and reporting.

This guide complements two other resources rather than replacing them:

- The **API reference** (Dokka/KDoc) documents every member of every class.
  Use it when you need the exact signature of a method.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)** explains how I/O fits
  into a simulation workflow. This guide links to it instead of repeating it.

This guide answers *"how do I accomplish X with this package?"* The runnable
examples are adapted from
`KSLExamples/src/main/kotlin/ksl/examples/book/appendixD` (`DemoIO`,
`DemoPlotting`) and the utility demos under
`KSLExamples/src/main/kotlin/ksl/examples/general/utilities`.

---

## 1. What this package is for

`ksl.utilities.io` is the KSL's **input/output toolkit**. It handles everything
that crosses the boundary between your running simulation and the outside world:
where output files go, how data is read and written in common formats, how
results land in databases, how to visualize data, and how to format reports.

Because it spans several unrelated formats, the package is best understood as a
set of focused areas rather than one API:

| Area | Entry points | Use it for... |
|---|---|---|
| **Output management** | `KSL`, `OutputDirectory` | A consistent place for all generated files. |
| **Files & arrays** | `KSLFileUtil` | Choosing files, scanning numeric data, writing arrays. |
| **CSV** | `CSVUtil` | Reading/writing comma-separated data. |
| **Excel** | `ExcelUtil` | Reading/writing `.xlsx` workbooks. |
| **Tabular files** | `tabularfiles.*` | A typed, columnar file format with random access. |
| **Data frames** | `DataFrameUtil` | Bridging to Kotlin DataFrame. |
| **Databases** | `dbutil.*` | Persisting results to SQLite/DuckDb/Derby/Postgres. |
| **Plotting** | `plotting.*` | Charts via the lets-plot library. |
| **Reporting** | `StatisticReporter`, `MarkDown`, `report.*` | Formatted text/Markdown/HTML output. |

### How it relates to its neighbors

This package is where the *outputs* of the other utility packages go: a
`Statistic` or `Histogram` from `ksl.utilities.statistic` gets reported by
`StatisticReporter` or charted by a class in `plotting`; samples from
`ksl.utilities.random` get written with `KSLFileUtil`.

---

## 2. The mental model

Two ideas organize almost everything.

1. **The `KSL` object is your output hub.** `KSL` is a singleton wrapping a
   default `OutputDirectory` (the `kslOutput` folder in the working directory).
   It exposes ready-made sub-directories — `KSL.csvDir`, `KSL.excelDir`,
   `KSL.dbDir`, `KSL.plotDir`, `KSL.outDir` — plus a text writer (`KSL.out`) and
   a logger (`KSL.logger`). Resolve paths against these directories so every
   artifact lands in a predictable place. When you want an *isolated* output
   location, create your own `OutputDirectory` instead.

2. **Most format helpers are stateless utility objects with symmetric
   read/write pairs.** `CSVUtil`, `ExcelUtil`, `KSLFileUtil`, `DataFrameUtil`,
   and `MarkDown` are Kotlin `object`s — you call their functions directly, no
   instance needed. They generally come in `writeXxx`/`readXxx` pairs. The
   exceptions are the *stateful* abstractions: `TabularOutputFile` /
   `TabularInputFile` (you create, write/read, then `close`/`flush`), the
   `dbutil` databases, and the `plotting` classes (you construct a plot object,
   then `showInBrowser`/`saveToFile`).

A handy unifying detail: every plot implements `PlotIfc`, so once you have any
plot you display it with `showInBrowser(...)` or persist it with
`saveToFile(...)` — uniformly.

---

## 3. Quick start

Send output to the standard KSL location, write an array, and persist a
statistic report.

```kotlin
import ksl.utilities.io.KSL
import ksl.utilities.io.writeToFile
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Statistic

fun main() {
    val sample = NormalRV(10.0, 4.0, streamNum = 1).sample(1000)

    // write the raw sample to a file inside kslOutput/
    sample.writeToFile("sample.txt")

    // general-purpose text output
    KSL.out.println("Generated ${sample.size} observations")

    // a formatted statistics report
    val stat = Statistic("Sample", sample)
    println(StatisticReporter(mutableListOf(stat)).halfWidthSummaryReport())
}
```

---

## 4. How do I...?

### ...control where output files go?

Use the `KSL` singleton for the default location, or an `OutputDirectory` for an
isolated one.

```kotlin
import ksl.utilities.io.KSL
import ksl.utilities.io.OutputDirectory
import java.nio.file.Paths

// Default hub: writes under kslOutput/ in the working directory
val pw = KSL.createPrintWriter("PW_File.txt")   // a file in kslOutput/
pw.println("Hello, World!")
KSL.createSubDirectory("SubDir")                // kslOutput/SubDir
KSL.out.println("goes to kslOutput.txt")        // the default text sink
KSL.logger.info { "an informational log message" }

// Isolated location: your own managed directory
val path = Paths.get("").toAbsolutePath().resolve("TestOutputDir")
val outDir = OutputDirectory(path)              // makes csvDir, excelDir, dbDir, out.txt
outDir.out.println("text into TestOutputDir/out.txt")
val sub = outDir.createSubDirectory("SubDir")
```

### ...let the user pick a file, or read numeric data from one?

`KSLFileUtil` covers common file chores.

```kotlin
import ksl.utilities.io.KSLFileUtil

val file = KSLFileUtil.chooseFile()             // a file-chooser dialog; may return null
if (file != null) {
    val data: DoubleArray = KSLFileUtil.scanToArray(file.toPath())  // one number per token
    println("read ${data.size} values")
}
```

### ...write arrays to a file?

`writeToFile` is available both as a `KSLFileUtil` function and as an extension
on numeric arrays.

```kotlin
import ksl.utilities.io.writeToFile
import ksl.utilities.io.KSLFileUtil

val data = doubleArrayOf(1.0, 2.0, 3.0)
data.writeToFile("data.txt")                        // extension form
KSLFileUtil.writeToFile(data, "data2.txt")          // utility form

val matrix = arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0))
matrix.writeToFile("matrix.txt")                    // 2-D arrays too
```

### ...read and write CSV?

```kotlin
import ksl.utilities.KSLArrays
import ksl.utilities.io.CSVUtil
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.NormalRV

// write a matrix with a header row
val matrix = NormalRV().sampleAsColumns(sampleSize = 5, nCols = 4)
val header = mutableListOf("col1", "col2", "col3", "col4")
val path = KSL.csvDir.resolve("data.csv")
CSVUtil.writeArrayToCSVFile(matrix, header = header, pathToFile = path)

// read it back (skip the header line), then parse to a 2-D double array
val rows: List<Array<String>> = CSVUtil.readRowsToListOfStringArrays(path, skipLines = 1)
val m = KSLArrays.parseTo2DArray(rows)
```

### ...read and write Excel?

```kotlin
import ksl.utilities.io.ExcelUtil

val map = mapOf("first" to 1.0, "second" to 2.0, "third" to Double.POSITIVE_INFINITY)
ExcelUtil.writeToExcel(map, "TestExcelMap")          // writes TestExcelMap.xlsx
val inMap = ExcelUtil.readToMap("TestExcelMap")
for ((key, value) in inMap) println("$key -> $value")
```

### ...write a typed tabular file?

`TabularOutputFile` is a columnar format with declared column types. Configure
columns, get a reusable `row`, fill it, write, then flush.

```kotlin
import ksl.utilities.io.KSL
import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.RowSetterIfc
import ksl.utilities.io.tabularfiles.TabularFile
import ksl.utilities.io.tabularfiles.TabularOutputFile
import ksl.utilities.random.rvariable.NormalRV

val path = KSL.outDir.resolve("demoFile")
// 3 numeric columns, then a text column, then another numeric column
val columns = TabularFile.columns(3, DataType.NUMERIC).toMutableMap()
columns["c4"] = DataType.TEXT
columns["c5"] = DataType.NUMERIC

val tif = TabularOutputFile(columns, path)
val n = NormalRV(10.0, 1.0)
val row: RowSetterIfc = tif.row()
for (i in 1..15) {
    row.setNumeric(n.sample(5))      // fill all numeric columns at once
    row.setText(3, "text data $i")   // set a specific column
    tif.writeRow(row)                // write the (reused) row to the buffer
}
tif.flushRows()                      // don't forget to flush
```

### ...read a tabular file (and convert it)?

`TabularInputFile` is `Iterable` over rows and offers random access plus
conversions to Excel, CSV, database, and data frame.

```kotlin
import ksl.utilities.io.KSL
import ksl.utilities.io.tabularfiles.RowGetterIfc
import ksl.utilities.io.tabularfiles.TabularInputFile

val tif = TabularInputFile(KSL.outDir.resolve("demoFile"))

for (row in tif.iterator()) println(row)                 // iterate all rows
val some: List<RowGetterIfc> = tif.fetchRows(1, 5)       // a subset
val col: DoubleArray = tif.fetchNumericColumn(0, 10, true) // a column as an array

tif.exportToExcelWorkbook("demoData.xlsx", KSL.excelDir) // -> Excel
val df = tif.asDataFrame()                                // -> Kotlin DataFrame
val db = tif.asDatabase()                                 // -> SQLite database
tif.printAsText(1, 5)                                     // pretty-print rows
tif.close()
```

### ...move data to or from a Kotlin DataFrame?

`DataFrameUtil` bridges data frames to KSL constructs and file formats.

```kotlin
import ksl.utilities.io.DataFrameUtil

// summarize a data-frame column
val stat = DataFrameUtil.statistics(doubleColumn)
val box = DataFrameUtil.boxPlotSummary(doubleColumn)

// write a whole data frame out as a tabular file
DataFrameUtil.toTabularFile(df, "DataFrameVersion")
```

### ...persist results to a database?

The `dbutil` package supports embedded SQLite, DuckDb, and Derby, plus Postgres.
Create an embedded database in `KSL.dbDir`:

```kotlin
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SQLiteDb

val db = SQLiteDb.createDatabase(dbName = "Results.db", dbDir = KSL.dbDir)
```

For capturing a model's simulation results automatically, attach a
`KSLDatabaseObserver` to the model (see `KSLDatabase` / `KSLDatabaseObserver`
and the appendix D plotting demo). `TabularInputFile.asDatabase()` is the
quickest way to get tabular data into SQLite.

### ...create plots?

Every plot implements `PlotIfc`, so the display/save calls are the same
regardless of plot type. Construct the plot, then `showInBrowser` or
`saveToFile`.

```kotlin
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.ScatterPlot
import ksl.utilities.random.rvariable.BivariateNormalRV
import ksl.utilities.random.rvariable.NormalRV

// a scatter plot of correlated data
val bvn = BivariateNormalRV(0.0, 1.0, 0.0, 1.0, 0.8)
val xy = bvn.sampleByColumn(1000)
val sp = ScatterPlot(xy[0], xy[1])
sp.showInBrowser(plotTitle = "Bivariate Normal")
sp.saveToFile("ScatterPlotDemo", plotTitle = "Bivariate Normal")

// run-order and autocorrelation views of a sample
val data = NormalRV().sample(500)
ObservationsPlot(data).showInBrowser()
ACFPlot(data).showInBrowser()
```

Common plots include `HistogramPlot`, `ObservationsPlot`, `ACFPlot`,
`ScatterPlot`, `BoxPlot` / `MultiBoxPlot`, `ConfidenceIntervalsPlot`,
`StateVariablePlot`, `QQPlot`, `PPPlot`, `ECDFPlot`, `FunctionPlot`,
`FitDistPlot`, `PMFPlot`, and the Welch-plot family. Several KSL classes also
offer convenience builders — e.g. `histogram.histogramPlot()` and
`frequency.frequencyPlot()`.

### ...format a quick text/Markdown report?

For a flat summary, `StatisticReporter` formats lists of statistics and
`MarkDown` builds Markdown fragments.

```kotlin
import ksl.utilities.io.MarkDown
import ksl.utilities.io.StatisticReporter
import ksl.utilities.statistic.Statistic

val reporter = StatisticReporter(mutableListOf(Statistic("X", doubleArrayOf(1.0, 2.0, 3.0))))
println(reporter.halfWidthSummaryReport())            // plain text
println(reporter.halfWidthSummaryReportAsMarkDown())  // Markdown

println(MarkDown.header("Results", 2))                // build Markdown directly
println(MarkDown.bold("important"))
```

For richer, multi-section documents that render to HTML, Markdown, plain text,
or LaTeX — with statistics cards, histograms, plots, and full simulation
results — use the `io.report` framework described in **Section 5**.

---

## 5. The `io.report` reporting framework

`StatisticReporter` and `MarkDown` (Section 4) give you flat, single-format
output. The `ksl.utilities.io.report` package is a step up: a **structured
document model** that you build once and render to **HTML, Markdown, plain text,
or LaTeX**. It is the engine behind the rich, browser-viewable reports the KSL
produces for simulations, distribution fitting, MODA, designed experiments, and
more.

### The model: build a `Document`, then render it

Everything centers on a `ReportNode.Document` — an in-memory report tree. You
obtain one in any of three ways, then call a render function on it. The render
functions are uniform across all three paths:

| Render call | Produces |
|---|---|
| `doc.showInBrowser()` | Renders HTML and opens it in the browser. |
| `doc.writeHtml()` / `writeMarkdown()` / `writeText()` / `writeLaTeX()` | Writes the document to a file (under `KSL.outDir` by default). |
| `doc.printText()` | Prints the plain-text rendering to the console. |
| `doc.toText()` / `doc.toMarkdown()` | Returns the rendering as a `String`. |

### Path 1 — zero-code: `x.toReport(...)`

The fastest path. Roughly forty KSL domain types have a `toReport(title)`
extension that produces a ready-to-render `Document`. Examples include
`StatisticIfc`, `Histogram`, `IntegerFrequency`, `BatchStatistic`,
`WeightedStatistic`, `Bootstrap`, `MultipleComparisonAnalyzer`, `PDFModeler`,
`AdditiveMODAModel`, `Controls`, `SimulationRun`, `DesignedExperiment`, and
`Model`.

```kotlin
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.statistic.Statistic

val stat = Statistic("Service Time", doubleArrayOf(9.6, 12.3, 9.5, 10.7, 13.6))
stat.toReport("Service Time Analysis").showInBrowser()   // a full statistic card
stat.toReport("Service Time Analysis").writeHtml()
```

### Path 2 — a full simulation report: `model.buildReport(...)`

`Model.buildReport(title)` assembles a complete report of a finished
simulation — across-replication statistics, histograms, and frequencies — in one
call.

```kotlin
import ksl.utilities.io.report.extensions.buildReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeMarkdown

val doc = model.buildReport("Drive-Through Pharmacy — Results")
doc.showInBrowser()
doc.writeMarkdown()
```

You can **append your own content** to the auto-generated report by passing a
DSL block (see Path 3 for the vocabulary):

```kotlin
model.buildReport("Results") {
    section("Notes") {
        paragraph("Base case: 1 server, 8-hour shift, 60-minute warm-up.")
    }
}.writeHtml()
```

### Path 3 — compose a custom document with the `report { }` DSL

`report(title) { ... }` opens a builder in which you assemble a document from
sections, prose, tables, plots, and domain-specific cards.

```kotlin
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.histogram
import ksl.utilities.io.report.extensions.statistic
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml

val doc = report("Service Time Analysis") {
    heading("Overview")
    paragraph("100 observations of customer service time.")
    statistic(myStat)            // a statistic card
    section("Distribution") {
        histogram(myHist)        // a histogram card
    }
}
doc.showInBrowser()
doc.writeHtml()
```

**Builder vocabulary.** The core `ReportBuilder` provides the structural
primitives:

| Primitive | Adds |
|---|---|
| `heading(text, level)` | A section heading. |
| `paragraph(text)` / `text(content)` | Prose / raw text. |
| `section(title) { ... }` | A nested, titled block. |
| `dataTable(...)` | A tabular block. |
| `statTable(...)` / `statPropertyTable(...)` | Statistic tables (summary / detail). |
| `plot(plotIfc, caption)` | An embedded plot (any `PlotIfc`). |
| `pageBreak()` | A page break (for paginated output). |

On top of these, each domain adds **builder extension functions** so you can drop
a ready-made card into the document — e.g. `statistic`, `statistics`,
`histogram`, `integerFrequency`, `stateFrequency`, `stringFrequency`,
`batchStatistic`, `weightedStatistic`, `bootstrap`, `regressionSummary`,
`multipleComparison`, `moda`, `goodnessOfFit`, `simulationResults`,
`responseTrace`, `welchAnalysis`, and the controls tables
(`numericControlsTable`, `stringControlsTable`, `controlsReport`). These are the
same cards the zero-code `toReport` path uses internally.

### Choosing a path

- Reach for **`toReport`** when you want one object documented quickly.
- Reach for **`buildReport`** to report a whole simulation.
- Reach for the **`report { }` DSL** to combine several artifacts (statistics,
  plots, prose) into one tailored document — and pass a block to `buildReport`
  to extend a simulation report with custom sections.

### How it renders

A `Document` is an AST of `ReportNode`s. Rendering walks the tree with a
visitor-based renderer — `HtmlReportRenderer`, `MarkdownReportRenderer`,
`TextReportRenderer`, or `LaTeXReportRenderer` — selected by the render call you
make. An optional `RenderContext` (and CSS, for HTML) lets you customize output
directories and styling.

---

## 6. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**Output management** — `KSL` (singleton hub: `out`, `logger`, `outDir`,
`csvDir`, `excelDir`, `dbDir`, `plotDir`, `createPrintWriter`,
`createSubDirectory`), `OutputDirectory` (an isolated managed directory),
`LogPrintWriter`.

**File & format utilities (stateless objects)** — `KSLFileUtil` (`chooseFile`,
`scanToArray`, `writeToFile`, `openInBrowser`, `createPrintWriter`), `CSVUtil`
(`writeArrayToCSVFile`, `readRowsToListOfStringArrays`), `ExcelUtil`
(`writeToExcel`, `readToMap`), `DataFrameUtil` (`statistics`, `boxPlotSummary`,
`toTabularFile`, `toCSV`), `MarkDown`.

**Tabular files (`io.tabularfiles`)** — `TabularFile` (column helpers,
`createFromCSVFile`), `TabularOutputFile` (write: `row`, `writeRow`,
`flushRows`), `TabularInputFile` (read: iterable, `fetchRows`,
`fetchNumericColumn`, `asDataFrame`, `asDatabase`, `exportToExcelWorkbook`),
`Row`/`RowSetterIfc`/`RowGetterIfc`, `DataType`.

**Databases (`io.dbutil`)** — `DatabaseIfc` / `Database` (the general contract),
embedded engines `SQLiteDb` / `DuckDb` / `DerbyDb` and `PostgresDb`,
`KSLDatabase` and `KSLDatabaseObserver` (auto-capture of simulation results),
`DbTableData` / `TabularData`.

**Plotting (`io.plotting`)** — `PlotIfc` (the common `showInBrowser` /
`saveToFile` contract) and the concrete plot classes listed above. Built on the
[lets-plot](https://github.com/JetBrains/lets-plot-kotlin) library.

**Reporting** — `StatisticReporter` and `MarkDown` for flat text/Markdown. The
`io.report` framework (Section 5) for structured documents: `ReportNode`
(the document AST), `ReportBuilder` and the `report { }` DSL (`io.report.dsl`),
the `toReport` / `buildReport` and per-domain builder extensions
(`io.report.extensions`), the render functions (`showInBrowser`, `writeHtml`,
`writeMarkdown`, `writeText`, `writeLaTeX`, `printText`), and the renderers
`HtmlReportRenderer` / `MarkdownReportRenderer` / `TextReportRenderer` /
`LaTeXReportRenderer` (`io.report.renderer`).

---

## 7. Gotchas and best practices

- **Route output through `KSL`'s directories.** Resolving paths against
  `KSL.csvDir`, `KSL.excelDir`, `KSL.dbDir`, and `KSL.plotDir` keeps artifacts
  organized and portable. Use a fresh `OutputDirectory` only when you need
  isolation.

- **Tabular output must be flushed; input must be closed.** Call
  `flushRows()` after writing a `TabularOutputFile` or buffered rows may be lost,
  and `close()` a `TabularInputFile` when done.

- **Reuse the `row` object when writing tabular files.** `tif.row()` returns a
  single reusable `RowSetterIfc`; refill and `writeRow` it in the loop rather
  than allocating a new row each iteration.

- **`chooseFile()` can return `null`.** It pops a dialog and the user may cancel;
  always null-check before using the result.

- **Plotting is uniform via `PlotIfc`.** Whatever the plot type, you display with
  `showInBrowser(plotTitle = ...)` and persist with
  `saveToFile(fileName, plotTitle = ...)`. Plots render through lets-plot to HTML.

- **Build a report once, render it many ways.** A `ReportNode.Document` is
  format-agnostic; the *render call* (`showInBrowser`, `writeHtml`,
  `writeMarkdown`, ...) picks the format. Build (or `toReport`/`buildReport`)
  once, then render to as many formats as you need.

- **`toReport` vs. `report { }` vs. `buildReport`.** Use `toReport` for a single
  object, `buildReport` for a whole simulation, and the `report { }` DSL to
  compose several artifacts — or pass a DSL block to `buildReport` to append
  custom sections to a simulation report.

- **Report DSL cards live in `io.report.extensions`.** The structural primitives
  (`paragraph`, `section`, `plot`, ...) are on `ReportBuilder`, but domain cards
  like `statistic`, `histogram`, and `simulationResults` are extension functions
  — import `ksl.utilities.io.report.extensions.*` to use them inside `report { }`.

- **The format utilities are `object`s, not classes.** Call
  `CSVUtil.writeArrayToCSVFile(...)`, `ExcelUtil.writeToExcel(...)`,
  `KSLFileUtil.scanToArray(...)` directly — there is nothing to instantiate.

- **Excel/DataFrame I/O pulls in heavier dependencies** (Apache POI, Kotlin
  DataFrame). That is expected; the functionality is part of `KSLCore`.

---

## 8. See also

- **What gets written/plotted:** `ksl.utilities.statistic` (`Statistic`,
  `Histogram`, `BoxPlotSummary`) and `ksl.utilities.distributions.fitting`
  (which uses many of these plots).
- **Generating the data:** `ksl.utilities.random`.
- **Runnable examples:** `DemoIO` and `DemoPlotting` under
  `KSLExamples/src/main/kotlin/ksl/examples/book/appendixD`, plus the database
  and tabular demos under
  `KSLExamples/src/main/kotlin/ksl/examples/general/utilities`.
- **Theory and workflow:** the [KSL Book](https://rossetti.github.io/KSLBook/).
