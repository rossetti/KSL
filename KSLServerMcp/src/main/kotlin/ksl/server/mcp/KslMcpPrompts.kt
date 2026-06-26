/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.server.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import ksl.service.capability.run.BundleInfo

/**
 * Guided "do-X" workflows exposed as MCP *prompts* (Phase 8.7). Where tools are
 * the verbs an agent calls, a prompt is a user-selectable starting point that
 * hands the agent a concrete, correctly-ordered plan stitched from those tools —
 * discovery → describe → author/validate → run → drill-in — so the agent does
 * not have to rediscover the document-centric workflow each time.
 *
 * The guidance text is rendered by pure functions ([runModelGuidance],
 * [optimizeGuidance], [fitGuidance], [exploreModelGuidance], [experimentGuidance],
 * [getStartedGuidance]) so it is directly unit-testable, and it is parameterized
 * with the *live* bundle catalog (reflecting Phase 8.6 dynamic loading) plus any
 * arguments the caller supplies. The server stays the source of the workflow; it
 * does not parse natural-language intent (that remains the agent's job, plan
 * §3.4 / §10).
 *
 * Each renderer follows a teaching loop (Orient → Do → Interpret → Check → Offer)
 * with a shared [teachingPreamble] and [glossary], so the prompts actively teach a
 * student, not just drive tools.
 */
object KslMcpPrompts {

    /** Registers the guided-workflow prompts on [server], backed by [tools]'s live catalog. */
    fun register(server: Server, tools: KslMcpTools) {
        server.addPrompt(
            name = "run_a_model",
            description = "Guided workflow: discover, describe, and run a single simulation of a " +
                "bundled model (quick run or full-fidelity RunConfiguration document).",
            arguments = listOf(
                PromptArgument(name = "bundleId", description = "Optional: the bundle to run from.", required = false),
                PromptArgument(name = "modelId", description = "Optional: the model to run.", required = false),
            ),
        ) { request ->
            userPrompt(
                "Run a KSL model",
                runModelGuidance(tools.availableBundles(), request.arguments?.get("bundleId"), request.arguments?.get("modelId")),
            )
        }

        server.addPrompt(
            name = "optimize_a_model",
            description = "Guided workflow: set up and run a simulation-optimization (minimize or " +
                "maximize a response over numeric decision variables) on a bundled model.",
            arguments = listOf(
                PromptArgument(name = "bundleId", description = "Optional: the bundle to optimize.", required = false),
                PromptArgument(name = "modelId", description = "Optional: the model to optimize.", required = false),
                PromptArgument(name = "objective", description = "Optional: the response name to optimize.", required = false),
            ),
        ) { request ->
            userPrompt(
                "Optimize a KSL model",
                optimizeGuidance(
                    tools.availableBundles(),
                    request.arguments?.get("bundleId"),
                    request.arguments?.get("modelId"),
                    request.arguments?.get("objective"),
                ),
            )
        }

        server.addPrompt(
            name = "fit_a_distribution",
            description = "Guided workflow: fit candidate probability distributions to a numeric " +
                "dataset and read the ranked fits and recommended family.",
            arguments = listOf(
                PromptArgument(name = "kind", description = "Optional: CONTINUOUS (default) or DISCRETE.", required = false),
            ),
        ) { request ->
            userPrompt("Fit a distribution to data", fitGuidance(request.arguments?.get("kind")))
        }

        server.addPrompt(
            name = "generate_random_variates",
            description = "Guided workflow: generate a random sample from a named probability distribution, " +
                "then summarize it or save it to a file.",
            arguments = listOf(
                PromptArgument(name = "familyId", description = "Optional: the distribution family to sample (e.g. 'exponential').", required = false),
            ),
        ) { request ->
            userPrompt("Generate random variates", generateVariatesGuidance(request.arguments?.get("familyId")))
        }

        server.addPrompt(
            name = "explore_a_model",
            description = "Guided workflow: understand a bundled model — its inputs, responses, and " +
                "supported study kinds — before running it.",
            arguments = listOf(
                PromptArgument(name = "bundleId", description = "Optional: the bundle to explore.", required = false),
                PromptArgument(name = "modelId", description = "Optional: the model to explore.", required = false),
            ),
        ) { request ->
            userPrompt(
                "Explore a KSL model",
                exploreModelGuidance(tools.availableBundles(), request.arguments?.get("bundleId"), request.arguments?.get("modelId")),
            )
        }

        server.addPrompt(
            name = "design_an_experiment",
            description = "Guided workflow: design and run a two-level factorial experiment over a bundled " +
                "model, previewing its cost and interpreting the factor effects.",
            arguments = listOf(
                PromptArgument(name = "bundleId", description = "Optional: the bundle to experiment on.", required = false),
                PromptArgument(name = "modelId", description = "Optional: the model to experiment on.", required = false),
            ),
        ) { request ->
            userPrompt(
                "Design a KSL experiment",
                experimentGuidance(tools.availableBundles(), request.arguments?.get("bundleId"), request.arguments?.get("modelId")),
            )
        }

        server.addPrompt(
            name = "get_started",
            description = "Guided workflow: the starting point — routes you to the right KSL workflow " +
                "(fit, explore, run, experiment, or optimize) based on your goal.",
            arguments = emptyList(),
        ) { _ ->
            userPrompt("Get started with KSL", getStartedGuidance(tools.availableBundles()))
        }
    }

    // ----- pure guidance renderers (unit-testable) -----

    /** The run-a-model plan, tailored to a chosen model when given, else listing the live catalog. */
    fun runModelGuidance(bundles: List<BundleInfo>, bundleId: String?, modelId: String?): String {
        val target = if (bundleId != null && modelId != null) {
            "Target model: `$modelId` in bundle `$bundleId`."
        } else {
            "No model chosen yet. Available now:\n${catalogMenu(bundles)}"
        }
        val body = """
            |You are helping the user run a single simulation of a KSL bundled model.
            |
            |$target
            |
            |Workflow:
            |1. Choose a (bundleId, modelId). Use `list_bundles` / `list_models` to refresh the live catalog.
            |2. Call `describe_model(bundleId, modelId)` to read its `responseNames`, its `inputSchema`
            |   (the valid input keys: numeric controls and RV parameters), and `supportedApps`.
            |3. Quick run: call `run_model(bundleId, modelId, numberOfReplications?, lengthOfReplication?, inputs?)`,
            |   where `inputs` is a `{inputKey: value}` map using keys from the `inputSchema`.
            |4. Full-fidelity, auditable run: call `run_template(bundleId, modelId)` for a ready-to-edit
            |   RunConfiguration. Edit the values, optionally `validate_run_config(config)`, then `run_config(config)`.
            |5. Run tools return the full result — a complete summary plus `structuredContent` — with a
            |   `resultId`. Drill in further with `get_result`, `list_responses`, and
            |   `get_response(resultId, name)` — no re-running.
            |
            |Interpret the result for the user:
            |- Report EVERY response with its average, standard error, and 95% CI half-width; then say
            |  what each average means for THIS model in plain terms (e.g. "the server was busy about
            |  63% of the time", "a customer waited ~4.2 minutes on average").
            |- Explain the 95% CI half-width as the ± margin around the average.
            |
            |Check soundness before trusting the numbers:
            |- Is each half-width small relative to its average? If a half-width is large, tell the user
            |  the estimate is imprecise and offer to run more replications.
            |- If this is a steady-state (long-run) model, mention warm-up: early observations can bias
            |  the averages, so a warm-up period may be warranted.
            |
            |Randomness control: identical runs reproduce by design (same streams → same numbers), so simply
            |re-running does NOT give a different answer. To get an independent, reproducible realization set
            |`replicationSet` (0 = the standard run; 1, 2, … each draw a fresh non-overlapping random block);
            |set `antithetic` for variance reduction. Use this when the user asks to "run it again differently"
            |or wants to see run-to-run variation.
            |
            |Offer a sensible next step:
            |- Compare input settings with a designed experiment (the `design_an_experiment` prompt),
            |- find the best inputs with an optimization (the `optimize_a_model` prompt), or
            |- drill into a single response with `get_response`.
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** The optimize-a-model plan, tailored to the chosen model/objective when given. */
    fun optimizeGuidance(bundles: List<BundleInfo>, bundleId: String?, modelId: String?, objective: String?): String {
        val target = if (bundleId != null && modelId != null) {
            "Target model: `$modelId` in bundle `$bundleId`."
        } else {
            "No model chosen yet. Available now:\n${catalogMenu(bundles)}"
        }
        val obj = objective?.let { "Objective response: `$it`." } ?: "Objective response: to be chosen from `describe_model`."
        val body = """
            |You are helping the user run a simulation-optimization over a KSL bundled model.
            |
            |$target
            |$obj
            |
            |Workflow:
            |1. Choose a model whose `supportedApps` includes SIMOPT (see `describe_model`).
            |2. From `describe_model`, identify the numeric decision variables (control keys, with bounds)
            |   and the response to optimize (`objectiveResponse`).
            |3. Quick path: `run_optimization(bundleId, modelId, objectiveResponse,
            |   inputs=[{name, lowerBound, upperBound, granularity}], maxIterations?, replicationsPerEvaluation?, maximize?)`.
            |4. Full-fidelity path: build an OptimizationConfiguration from the decision variables,
            |   `validate_optimization_config(config)`, then `run_optimization_config(config)`.
            |5. Read the best solution and iteration history from the result via `get_result(resultId)`.
            |
            |Cost first: before running, call `preview_optimization_config(config)` and tell the user the
            |budget — iterations × replications per evaluation is a lower bound on the simulation work.
            |
            |Interpret the result for the user:
            |- Report the best solution (the decision-variable values it found) and its objective value,
            |  in plain terms ("the lowest average wait, ~3.1 min, was at 2 servers").
            |- Summarize the iteration history: did the objective improve and then level off?
            |
            |Check soundness before trusting the result:
            |- Did it converge, or was the objective still improving at the last iteration? If still
            |  improving, offer to raise `maxIterations`.
            |- Is `replicationsPerEvaluation` large enough to tell a real improvement from simulation
            |  noise? With too few, the "best" may just be lucky; offer to increase it.
            |
            |Offer a sensible next step: re-run with tighter bounds or more replications per evaluation,
            |or a designed experiment (`design_an_experiment`) to understand how each input drives the response.
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** The fit-a-distribution plan. */
    fun fitGuidance(kind: String?): String {
        val chosen = kind?.uppercase()?.takeIf { it == "CONTINUOUS" || it == "DISCRETE" } ?: "CONTINUOUS"
        val body = """
            |You are helping the user fit probability distributions to a numeric dataset
            |(KSL's distribution-fitting capability).
            |
            |Distribution kind: $chosen${if (kind == null) " (default)" else ""}.
            |
            |Workflow:
            |1. Collect the observations as an array of numbers (at least two values).
            |2. Confirm the kind: CONTINUOUS (default) for measurements, DISCRETE for counts.
            |3. Optional but recommended: call `summarize_data(data)` first for an exploratory summary
            |   (count, mean, variance, min/max, and a histogram) to sanity-check the data before fitting.
            |4. Call `fit_dataset(data=[...], name?, kind?)`.
            |5. The candidates are ranked by a MODA scaled score (a 0–1 multi-objective score over weighted
            |   metrics — NOT a raw goodness-of-fit statistic), and the top-scoring family is recommended.
            |   Report the recommended family with its parameters and its scaled MODA score, then the full
            |   ranking; note close alternatives (scores within noise of each other).
            |6. Offer the diagnostics: `get_fit_scoring(resultId)` for the full scaled-score matrix,
            |   `get_fit_data_summary(resultId)` for the data summary + histogram the fit computed, and
            |   `get_fit_report(resultId)` for the HTML report with density / ECDF / Q-Q / P-P plots.
            |
            |Interpret the result for the user:
            |- Lead with the recommended family, its parameters, and its scaled MODA score; then the full
            |  ranking. Say in plain terms what the distribution implies (e.g. "an exponential service
            |  time means most jobs are quick but a few take much longer").
            |
            |Check soundness before trusting the fit:
            |- How many observations are there? A small sample gives unstable fits — say so.
            |- Are the top alternatives within noise of each other (very close MODA scores)? If so, tell
            |  the user the choice is not clear-cut and the diagnostic plots help decide.
            |
            |Offer a sensible next step: `get_fit_report` for the visual diagnostics, or use the fitted
            |distribution as a random-input to a model run (the `run_a_model` prompt).
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** The generate-random-variates plan: the forward direction (distribution → samples). */
    fun generateVariatesGuidance(familyId: String?): String {
        val target = familyId?.let { "Chosen family: `$it`." }
            ?: "No family chosen yet — call `list_distributions` to see the available families."
        val body = """
            |You are helping the user generate a random sample from a probability distribution
            |(KSL's variate-generation capability — the forward direction, distribution → data).
            |
            |$target
            |
            |Workflow:
            |1. Call `list_distributions` to see the scalar-parameter families — each entry gives a
            |   `familyId`, its `kind` (CONTINUOUS / DISCRETE), and its scalar parameters with catalog defaults.
            |2. Pick a `familyId` and decide the parameter values. Defaults are used for any parameter you
            |   don't supply, so pass only the `{paramName: value}` overrides you want to change.
            |3. Call `generate_variates(familyId, n, parameters?, name?, output?)`. `n` is 1–10000. The full
            |   sample is written to a CSV under the workspace data directory when you pass `output=true`, or
            |   automatically when `n` exceeds 1000 — then `values` carries a leading preview (`truncated=true`)
            |   and `filePath` points to the complete sample.
            |4. Inspect the sample: `summarize_data(data)` gives the statistics and a histogram; or feed the
            |   sample to `fit_dataset` to recover the distribution (a useful round-trip check).
            |
            |Interpret the result for the user:
            |- State the family and the exact parameter values used, the sample size, and a few representative
            |  values (or the range/mean). When a file was written, give the user its `filePath`.
            |- Say what the distribution implies in plain terms (e.g. "an exponential with mean 10 produces
            |  mostly small values with an occasional large one").
            |
            |Check soundness before trusting the sample:
            |- A finite sample only approximates the distribution — a small `n` is noisy. The sample mean and
            |  variance should be near the distribution's theoretical values; `summarize_data` confirms this.
            |- If the user needs reproducibility, note that regenerating draws a fresh random sample each time.
            |
            |Offer a sensible next step: `summarize_data` the sample, `fit_dataset` to recover the family
            |(round-trip), or use the distribution as a random input to a model run (the `run_a_model` prompt).
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** The explore-a-model plan: understand a model's inputs, responses, and study kinds before running. */
    fun exploreModelGuidance(bundles: List<BundleInfo>, bundleId: String?, modelId: String?): String {
        val target = if (bundleId != null && modelId != null) {
            "Target model: `$modelId` in bundle `$bundleId`."
        } else {
            "No model chosen yet. Available now:\n${catalogMenu(bundles)}"
        }
        val body = """
            |You are helping the user understand a KSL model BEFORE running anything.
            |
            |$target
            |
            |Workflow:
            |1. Pick a (bundleId, modelId) — `list_bundles` / `list_models` show the live catalog.
            |2. Call `describe_model(bundleId, modelId)`.
            |3. Explain, in plain terms, what the model is and how to use it:
            |   - inputs (`inputSchema`): each control key / RV parameter — what it represents and its units.
            |   - responses (`responseNames`): what each output measures (e.g. utilization, waiting time).
            |   - `supportedApps`: the studies the model supports (SINGLE, SCENARIO, EXPERIMENT, SIMOPT) and what each means.
            |
            |Teach, don't dump: connect inputs to outputs ("raising the service rate should lower the wait"),
            |and define any domain term the first time it appears.
            |
            |Offer a sensible next step for the user's goal: run one scenario (`run_a_model`), compare
            |settings (`design_an_experiment`), find the best settings (`optimize_a_model`), or fit a
            |distribution to data (`fit_a_distribution`).
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** The design-an-experiment plan: a two-level factorial, with cost preview and effect interpretation. */
    fun experimentGuidance(bundles: List<BundleInfo>, bundleId: String?, modelId: String?): String {
        val target = if (bundleId != null && modelId != null) {
            "Target model: `$modelId` in bundle `$bundleId`."
        } else {
            "No model chosen yet. Available now:\n${catalogMenu(bundles)}"
        }
        val body = """
            |You are helping the user design and run a two-level factorial experiment over a KSL model —
            |varying inputs (factors) to see how they drive the responses.
            |
            |$target
            |
            |Workflow:
            |1. Pick a model and call `describe_model` — a factorial needs at least two numeric controls
            |   (the factors). Each factor gets a low and a high level.
            |2. Scaffold with `experiment_template(bundleId, modelId)` (a two-level factorial over the first
            |   two numeric controls) and edit the factor levels, or build the factors directly.
            |3. COST FIRST: call `preview_experiment_config(config)` and tell the user the budget — the
            |   design-point count (2^k for k factors) and total replications — before running.
            |4. Validate with `validate_experiment_config(config)`, then run with `experiment_config(config)`
            |   (or the quick `run_experiment(bundleId, modelId, factors=[...], numRepsPerDesignPoint?)`).
            |5. The result is a batch — one row per design point. Drill into any with `get_design_point(resultId, index)`.
            |
            |Interpret the result for the user:
            |- Report each design point (its factor settings → response means).
            |- Point out the main effect of each factor: how much the response changed from the factor's low
            |  to high level — in plain terms.
            |
            |Check soundness:
            |- Are there enough replications per design point to tell a real effect from simulation noise?
            |- Remind the user the cost grows as 2^k — each added factor doubles the design points.
            |
            |Offer a sensible next step: once the influential factors are known, find the best settings with
            |an optimization (`optimize_a_model`).
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** The umbrella "get started" router: matches the user's goal to the right guided workflow. */
    fun getStartedGuidance(bundles: List<BundleInfo>): String {
        val body = """
            |You are helping the user get started with KSL: discrete-event simulation over bundled models,
            |plus probability-distribution fitting. Route them to the right workflow for their goal.
            |
            |Available models now:
            |${catalogMenu(bundles)}
            |
            |Pick the path that matches the user's goal:
            |- "I have a dataset and want a probability distribution" → the `fit_a_distribution` prompt.
            |- "I want to generate random data from a distribution" → the `generate_random_variates` prompt.
            |- "I have numbers and just want their summary statistics / a histogram" → call `summarize_data`.
            |- "I want to understand what a model does" → the `explore_a_model` prompt.
            |- "Run one scenario and read the outputs" → the `run_a_model` prompt.
            |- "Compare input settings / find which inputs matter" → the `design_an_experiment` prompt.
            |- "Find the input settings that minimize or maximize a response" → the `optimize_a_model` prompt.
            |
            |If the user is unsure, ask one or two clarifying questions (do they have data, or a model and a
            |question?), then start the matching workflow. Define terms as you go.
        """.trimMargin()
        return assemble(teachingPreamble(), body, glossary())
    }

    /** Joins the non-blank sections of a prompt with blank-line separators. */
    private fun assemble(vararg sections: String): String =
        sections.filter { it.isNotBlank() }.joinToString("\n\n")

    /** A teaching stance prepended to every guided workflow: the prompts should *teach*, not just
     *  drive tools, because the user may be a student new to simulation. */
    private fun teachingPreamble(): String =
        """
        |Teach as you go — assume the user may be a student new to simulation:
        |- Define each term the first time you use it (see the glossary at the end).
        |- Show the numbers AND say what they mean in plain language; don't just dump output.
        |- Simulation results are estimates with uncertainty: never over-claim from a single
        |  replication, and always surface the margin of error (the 95% CI half-width).
        |- Before an expensive run, preview its cost (the `preview_*` tools) and tell the user the
        |  replication budget first, so they can decide.
        """.trimMargin()

    /** Shared definitions injected into each prompt, so terms are explained once and consistently. */
    private fun glossary(): String =
        """
        |Glossary (define these on first use):
        |- replication: one independent run of the model; more replications → less sampling error in
        |  the reported averages.
        |- 95% CI half-width: the ± margin on a response average. Small relative to the average means a
        |  precise estimate; if it's large, run more replications.
        |- warm-up / steady-state: early observations can be biased by the empty starting state;
        |  steady-state studies discard a warm-up period before collecting statistics.
        |- decision variable: an input the optimizer may change, within stated bounds.
        |- design point: one combination of factor settings in a designed experiment.
        |- MODA scaled score: the 0–1 multi-objective score (value functions over weighted metrics)
        |  used to rank fitted distributions; higher is better. It is NOT a raw goodness-of-fit number.
        |- random variate: one random draw from a probability distribution; a sample is n independent variates.
        |- distribution family: a named parametric distribution (Normal, Exponential, …) with scalar
        |  parameters (e.g. mean, variance) that you set or leave at their defaults.
        |- replicationSet: an independent random-realization selector for a run; 0 is the standard run, and
        |  each value draws from a fresh non-overlapping block of random substreams — reproducible, but different.
        """.trimMargin()

    private fun catalogMenu(bundles: List<BundleInfo>): String =
        if (bundles.isEmpty()) {
            "  (no bundles are currently loaded — drop a bundle JAR into ~/.ksl/bundles/, or check the classpath)"
        } else {
            bundles.joinToString("\n") { b -> "  - `${b.bundleId}`: ${b.modelIds.joinToString(", ")}" }
        }

    private fun userPrompt(description: String, body: String): GetPromptResult =
        GetPromptResult(
            messages = listOf(PromptMessage(role = Role.User, content = TextContent(body))),
            description = description,
        )
}
