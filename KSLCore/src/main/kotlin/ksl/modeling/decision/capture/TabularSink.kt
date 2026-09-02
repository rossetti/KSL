package ksl.modeling.decision.capture

import kotlinx.serialization.json.Json
import ksl.modeling.decision.RunProvenance
import ksl.modeling.decision.descriptor.EpochProvenance
import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.TabularInputFile
import ksl.utilities.io.tabularfiles.TabularOutputFile
import java.nio.file.Files
import java.nio.file.Path

/**
 *  A durable [TransitionSink]: one flat table of transitions, written through KSL's
 *  [TabularOutputFile], plus the provenance that makes the table readable by something that has
 *  never seen the model.
 *
 *  ### Two files, and the second one is not optional
 *
 *  A transition is positional. `a_Mode = 2.0` means nothing without the declaration saying that
 *  lever is `CATEGORICAL` over `["slow", "normal", "fast"]`, that it is a `SETTING` rather than a
 *  `TRANSACTION` — which is a fact about the dynamics, not decoration — and where its bounds are.
 *  Naming columns from the descriptor carries position-to-name and nothing else. So this sink
 *  writes both:
 *
 *  - `<name>.sqlite` — the rows. It is an ordinary SQLite database (that is what a
 *    `TabularOutputFile` is), so it is queryable with any SQL tool and readable from Python.
 *  - `<name>.provenance.json` — the [RunProvenance]: model, experiment, element, policy label, and
 *    the full descriptor.
 *
 *  [TrajectoryFile] pairs them on read and **refuses** a trajectory whose provenance is missing,
 *  rather than guessing. Guessing is how a categorical index quietly becomes a continuous one.
 *
 *  ### Column names are sanitised, and that is not defensive
 *
 *  `TabularOutputFile` interpolates column names straight into `CREATE TABLE`. Measured: a `:` or a
 *  `-` in a name fails the create outright, and a *space* is worse — SQLite parses `s_L Queue` as a
 *  column `s_L` of type `Queue`, so the file is created with a silently wrong column. KSL element
 *  names are colon-qualified by convention (`Room:Position`), so every realistic name hits this.
 *  Names are therefore reduced to `[A-Za-z0-9_]`, and a collision produced by that reduction is
 *  refused at construction with both original names in the message.
 *
 *  ### The schema
 *
 *  For *n* observations and *m* levers: `10 + 2n + 3m` columns.
 *
 *  | Column | Type | Meaning |
 *  |---|---|---|
 *  | `element`, `rep`, `epoch` | TEXT, NUMERIC, NUMERIC | which element, replication and decision |
 *  | `time`, `tau` | NUMERIC | the successor's time, and the interval this row covers |
 *  | `s_*`, `sp_*` | NUMERIC | state and successor state, one column per declared observation |
 *  | `a_*` | NUMERIC | the action **applied** |
 *  | `p_*` | NUMERIC | the action **proposed**. Always written; equal to `a_*` when nothing was repaired |
 *  | `repaired` | NUMERIC | 1 when the rule's request was repaired, so `p_*` differs from `a_*` |
 *  | `unavail_*` | NUMERIC | 1 when that lever's feasible set was empty and it took its neutral |
 *  | `reward` | NUMERIC | already sign-normalised: **larger is better**, because `COST` is negated once at declaration |
 *  | `terminated`, `truncated` | NUMERIC | 0/1, and deliberately separate — a learner that bootstraps from the last row needs to know which it got |
 *  | `source` | TEXT | why the episode ended; `""` when it had not |
 *
 *  **Nulls are encoded rather than stored.** `Row.setNumeric` takes a non-null `Double` and
 *  `DataType` has only `NUMERIC` and `TEXT`, so the three nullable fields of a [TransitionRecord]
 *  need a convention. `proposedAction` and `leverUnavailable` are always written, with `repaired`
 *  saying whether the proposal is news; `source` is `""` for absent. `NaN` was rejected as the null
 *  marker: a `NaN` in training data is a trap that surfaces three steps later inside somebody's
 *  learner.
 */
class TabularSink(
    private val provenance: RunProvenance,
    /** Where the trajectory goes. `.sqlite` is appended if absent. */
    path: Path
) : TransitionSink {

    private val descriptor: DecisionSurfaceDescriptor = provenance.descriptor

    /** The trajectory file. */
    val rowsPath: Path = if (path.fileName.toString().endsWith(SUFFIX)) path
    else path.resolveSibling(path.fileName.toString() + SUFFIX)

    /** The provenance beside it. */
    val provenancePath: Path = provenanceFor(rowsPath)

    private val columns: LinkedHashMap<String, DataType> = buildColumns()
    private val file: TabularOutputFile
    private var written = 0L

    /** How many rows this sink has taken. */
    val rowsWritten: Long get() = written

    init {
        Files.createDirectories(rowsPath.parent)
        Files.writeString(provenancePath, PROVENANCE_JSON.encodeToString(RunProvenance.serializer(), provenance))
        file = TabularOutputFile(columns, rowsPath)
    }

    override fun write(record: TransitionRecord) {
        val row = file.row()
        row.setText("element", record.elementName)
        row.setNumeric("rep", record.replicationId.toDouble())
        row.setNumeric("epoch", record.epochIndex.toDouble())
        row.setNumeric("time", record.time)
        row.setNumeric("tau", record.tau)

        for ((i, o) in descriptor.observations.withIndex()) {
            row.setNumeric(stateCol(o.name), record.state[i])
            row.setNumeric(successorCol(o.name), record.successorState[i])
        }
        val proposed = record.proposedAction
        val unavailable = record.leverUnavailable
        for ((j, l) in descriptor.levers.withIndex()) {
            row.setNumeric(actionCol(l.name), record.action[j])
            row.setNumeric(proposedCol(l.name), proposed?.get(j) ?: record.action[j])
            row.setNumeric(unavailableCol(l.name), flag(unavailable?.get(j) ?: false))
        }
        row.setNumeric("repaired", flag(proposed != null))
        row.setNumeric("reward", record.reward)
        row.setNumeric("terminated", flag(record.terminated))
        row.setNumeric("truncated", flag(record.truncated))
        row.setText("source", record.source?.name ?: "")
        row.setText("reason", record.reason)
        row.setText("provenance", record.provenance.name)

        file.writeRow(row)
        written++
    }

    override fun close() {
        file.flushRows()
        file.close()
    }

    /** `RowSetterIfc` has a boolean overload by column *index* only, so encode by name here. */
    private fun flag(b: Boolean): Double = if (b) 1.0 else 0.0

    private fun buildColumns(): LinkedHashMap<String, DataType> {
        val cols = LinkedHashMap<String, DataType>()
        // Where each column name came from, so a collision can name both originals.
        val origin = HashMap<String, String>()

        fun put(name: String, type: DataType, from: String) {
            val clash = origin[name]
            require(clash == null) {
                "Two columns of this trajectory reduce to '$name': '$clash' and '$from'. Column " +
                    "names are reduced to [A-Za-z0-9_] because TabularOutputFile writes them " +
                    "straight into CREATE TABLE, and these two are no longer distinguishable. " +
                    "Rename one of the declarations."
            }
            origin[name] = from
            cols[name] = type
        }

        put("element", DataType.TEXT, "element")
        for (n in listOf("rep", "epoch", "time", "tau")) put(n, DataType.NUMERIC, n)
        for (o in descriptor.observations) put(stateCol(o.name), DataType.NUMERIC, "observation ${o.name}")
        for (l in descriptor.levers) {
            put(actionCol(l.name), DataType.NUMERIC, "lever ${l.name}")
            put(proposedCol(l.name), DataType.NUMERIC, "lever ${l.name} (proposed)")
            put(unavailableCol(l.name), DataType.NUMERIC, "lever ${l.name} (unavailable)")
        }
        put("repaired", DataType.NUMERIC, "repaired")
        put("reward", DataType.NUMERIC, "reward")
        for (o in descriptor.observations) put(successorCol(o.name), DataType.NUMERIC, "successor ${o.name}")
        for (n in listOf("terminated", "truncated")) put(n, DataType.NUMERIC, n)
        put("source", DataType.TEXT, "source")
        // S§C.11.3. Both belong to the epoch that OPENED the interval, and both are recorded
        // rather than left to be inferred: under caller-owned timing the interval length is
        // nearly a giveaway for which entry point was used, and inferring from a near-giveaway
        // is how a subtle bias enters a learner.
        put("reason", DataType.TEXT, "reason")
        put("provenance", DataType.TEXT, "provenance")
        return cols
    }

    companion object {
        const val SUFFIX = ".sqlite"
        const val PROVENANCE_SUFFIX = ".provenance.json"

        internal val PROVENANCE_JSON = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

        /** The provenance file that belongs to [rows]. */
        fun provenanceFor(rows: Path): Path =
            rows.resolveSibling(rows.fileName.toString().removeSuffix(SUFFIX) + PROVENANCE_SUFFIX)

        /**
         *  Reduces [name] to something `CREATE TABLE` accepts. Every character outside
         *  `[A-Za-z0-9_]` becomes `_`.
         */
        fun sanitize(name: String): String = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

        fun stateCol(observation: String): String = "s_" + sanitize(observation)
        fun successorCol(observation: String): String = "sp_" + sanitize(observation)
        fun actionCol(lever: String): String = "a_" + sanitize(lever)
        fun proposedCol(lever: String): String = "p_" + sanitize(lever)
        fun unavailableCol(lever: String): String = "unavail_" + sanitize(lever)
    }
}

/**
 *  A trajectory read back **with no live `Model`** — which is the whole point of writing one.
 *
 *  Opening pairs the rows with their provenance and fails if the provenance is absent, because a
 *  positional row without its descriptor cannot be interpreted and a reader that proceeds anyway
 *  would be inventing meaning. [provenance] then answers what the columns cannot: domains,
 *  categorical levels, bounds, lever kinds, units, reward senses, and the schema version.
 */
class TrajectoryFile(rowsPath: Path) : AutoCloseable {

    /** What produced these rows, including the descriptor that gives them meaning. */
    val provenance: RunProvenance

    private val input: TabularInputFile

    init {
        val provPath = TabularSink.provenanceFor(rowsPath)
        require(Files.exists(rowsPath)) { "No trajectory at $rowsPath." }
        require(Files.exists(provPath)) {
            "The trajectory at $rowsPath has no provenance beside it (expected $provPath). Its rows " +
                "are positional — an action column holds a bare number whose meaning is in the " +
                "descriptor — so they cannot be interpreted without it. This is refused rather " +
                "than guessed."
        }
        provenance = TabularSink.PROVENANCE_JSON.decodeFromString(
            RunProvenance.serializer(), Files.readString(provPath))
        input = TabularInputFile(rowsPath)
    }

    val descriptor: DecisionSurfaceDescriptor get() = provenance.descriptor

    /** How many transitions are stored. */
    val rowCount: Long get() = input.totalNumberRows

    /** Every transition, as `(state, action, reward, successorState)` plus the episode flags. */
    fun transitions(): List<StoredTransition> {
        val obs = descriptor.observations.map { it.name }
        val lev = descriptor.levers.map { it.name }
        return input.fetchRows(1, rowCount).map { r ->
            StoredTransition(
                replicationId = r.getNumeric("rep").toInt(),
                epochIndex = r.getNumeric("epoch").toInt(),
                time = r.getNumeric("time"),
                tau = r.getNumeric("tau"),
                state = DoubleArray(obs.size) { r.getNumeric(TabularSink.stateCol(obs[it])) },
                action = DoubleArray(lev.size) { r.getNumeric(TabularSink.actionCol(lev[it])) },
                reward = r.getNumeric("reward"),
                successorState = DoubleArray(obs.size) { r.getNumeric(TabularSink.successorCol(obs[it])) },
                terminated = r.getNumeric("terminated") != 0.0,
                truncated = r.getNumeric("truncated") != 0.0,
                repaired = r.getNumeric("repaired") != 0.0,
                reason = r.getText("reason") ?: "",
                provenance = r.getText("provenance")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { EpochProvenance.valueOf(it) }
                    ?: EpochProvenance.IMMEDIATE
            )
        }
    }

    override fun close() { input.close() }
}

/** One transition as read back from a [TrajectoryFile]. */
class StoredTransition(
    val replicationId: Int,
    val epochIndex: Int,
    val time: Double,
    val tau: Double,
    val state: DoubleArray,
    val action: DoubleArray,
    val reward: Double,
    val successorState: DoubleArray,
    val terminated: Boolean,
    val truncated: Boolean,
    val repaired: Boolean,
    /** Why the decision that opened this interval was taken. */
    val reason: String = "",
    /** Where the state on this row was read (S§C.11.3). */
    val provenance: EpochProvenance = EpochProvenance.IMMEDIATE
)
