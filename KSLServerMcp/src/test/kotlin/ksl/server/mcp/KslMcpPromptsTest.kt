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

import ksl.service.capability.run.BundleRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the Phase 8.7 guided-workflow prompts: that the server advertises them,
 * and that the pure guidance renderers reflect the live catalog and the chosen
 * model, and point the agent at real tool names in the right order.
 */
class KslMcpPromptsTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        tools = KslMcpTools(registry, ksl.service.store.ResultStore(java.nio.file.Files.createTempDirectory("mcp-prompts-rs")))
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    @Test
    fun `server registers the guided-workflow prompts`() {
        val server = KslMcpServer.build(tools)
        val names = server.prompts.keys
        for (p in listOf(
            "run_a_model", "optimize_a_model", "fit_a_distribution", "generate_random_variates",
            "explore_a_model", "design_an_experiment", "get_started",
        )) {
            assertTrue(p in names, "expected $p in $names")
        }
    }

    @Test
    fun `explore guidance describes a model before running and routes onward`() {
        val text = KslMcpPrompts.exploreModelGuidance(tools.availableBundles(), "ksl.examples.mm1", "MM1")
        assertTrue("describe_model" in text, "explore should center on describe_model")
        assertTrue("responseNames" in text && "inputSchema" in text, "should explain inputs and responses")
        assertTrue("design_an_experiment" in text || "run_a_model" in text, "should offer onward workflows")
    }

    @Test
    fun `experiment guidance previews cost, runs the factorial, and warns about the 2^k blow-up`() {
        val text = KslMcpPrompts.experimentGuidance(tools.availableBundles(), "ksl.examples.mm1", "MM1")
        assertTrue("preview_experiment_config" in text, "should preview cost before running")
        assertTrue("run_experiment" in text || "experiment_config" in text, "should reference the experiment tools")
        assertTrue("2^k" in text, "should warn about the 2^k design-point growth")
        assertTrue("get_design_point" in text, "should point at per-design-point drill-in")
    }

    @Test
    fun `get_started routes by goal and lists the live catalog`() {
        val text = KslMcpPrompts.getStartedGuidance(tools.availableBundles())
        assertTrue("ksl.examples.mm1" in text, "should list the live catalog")
        for (p in listOf(
            "fit_a_distribution", "generate_random_variates", "explore_a_model",
            "run_a_model", "design_an_experiment", "optimize_a_model",
        )) {
            assertTrue(p in text, "router should mention the $p path")
        }
        assertTrue("summarize_data" in text, "router should offer the summarize_data shortcut")
    }

    @Test
    fun `generate-variates guidance discovers families, generates, and routes onward`() {
        val text = KslMcpPrompts.generateVariatesGuidance(null)
        assertTrue("list_distributions" in text, "should start from list_distributions")
        assertTrue("generate_variates" in text, "should call generate_variates")
        assertTrue("summarize_data" in text && "fit_dataset" in text, "should offer summary and round-trip fit")
        assertTrue("Interpret the result" in text && "Check soundness" in text, "should teach the loop")
        assertTrue("Teach as you go" in text && "Glossary" in text, "should carry the preamble and glossary")
        // Names the chosen family when given.
        assertTrue("exponential" in KslMcpPrompts.generateVariatesGuidance("exponential"), "should echo the chosen family")
    }

    @Test
    fun `fit guidance offers the exploratory summary and the fit data summary`() {
        val text = KslMcpPrompts.fitGuidance(null)
        assertTrue("summarize_data" in text, "fit guidance should suggest summarize_data for EDA")
        assertTrue("get_fit_data_summary" in text, "fit guidance should offer get_fit_data_summary among the diagnostics")
    }

    @Test
    fun `run-a-model guidance lists the live catalog when no model is chosen`() {
        val text = KslMcpPrompts.runModelGuidance(tools.availableBundles(), null, null)
        assertTrue("ksl.examples.mm1" in text, "expected the live MM1 bundle in the menu: $text")
        assertTrue("describe_model" in text && "run_config" in text, "must reference the real tools")
    }

    @Test
    fun `run-a-model guidance names the chosen model when given`() {
        val text = KslMcpPrompts.runModelGuidance(emptyList(), "ksl.examples.mm1", "MM1")
        assertTrue("MM1" in text && "ksl.examples.mm1" in text, "should name the chosen target: $text")
    }

    @Test
    fun `optimize guidance points at the simopt tools and the objective`() {
        val text = KslMcpPrompts.optimizeGuidance(emptyList(), "ksl.examples.mm1", "MM1", "System Time")
        assertTrue("SIMOPT" in text, "should steer toward SIMOPT-capable models")
        assertTrue("run_optimization" in text, "should reference run_optimization")
        assertTrue("System Time" in text, "should echo the chosen objective")
    }

    @Test
    fun `fit guidance defaults to CONTINUOUS and honors DISCRETE`() {
        assertTrue("CONTINUOUS (default)" in KslMcpPrompts.fitGuidance(null))
        assertTrue("DISCRETE" in KslMcpPrompts.fitGuidance("discrete"))
        assertTrue("fit_dataset" in KslMcpPrompts.fitGuidance(null))
    }

    @Test
    fun `every prompt carries the teaching preamble and glossary`() {
        val texts = listOf(
            KslMcpPrompts.runModelGuidance(tools.availableBundles(), null, null),
            KslMcpPrompts.optimizeGuidance(emptyList(), "ksl.examples.mm1", "MM1", null),
            KslMcpPrompts.fitGuidance(null),
        )
        for (text in texts) {
            assertTrue("Teach as you go" in text, "missing the teaching preamble: $text")
            assertTrue("Glossary" in text, "missing the glossary: $text")
            assertTrue("95% CI half-width" in text, "the glossary should define the CI half-width")
        }
    }

    @Test
    fun `run guidance no longer claims a compact card and reflects structured results`() {
        val text = KslMcpPrompts.runModelGuidance(tools.availableBundles(), "ksl.examples.mm1", "MM1")
        assertTrue("compact card" !in text, "stale 'compact card' wording should be gone: $text")
        assertTrue("structuredContent" in text, "should reflect the structured result envelope")
    }

    @Test
    fun `fit guidance ranks by MODA not raw goodness-of-fit and offers the diagnostics`() {
        val text = KslMcpPrompts.fitGuidance(null)
        assertTrue("MODA scaled score" in text, "fit ranking is by the scaled MODA score")
        assertTrue("by goodness-of-fit and recommends" !in text, "the old GOF-recommendation wording must be gone")
        assertTrue("get_fit_scoring" in text && "get_fit_report" in text, "should offer the fit diagnostics")
    }

    @Test
    fun `every prompt teaches the loop - interpret, check soundness, offer next`() {
        // The result-producing prompts must teach interpretation + a soundness check, not just run tools.
        val texts = listOf(
            KslMcpPrompts.runModelGuidance(tools.availableBundles(), "ksl.examples.mm1", "MM1"),
            KslMcpPrompts.optimizeGuidance(emptyList(), "ksl.examples.mm1", "MM1", "System Time"),
            KslMcpPrompts.fitGuidance(null),
            KslMcpPrompts.experimentGuidance(tools.availableBundles(), "ksl.examples.mm1", "MM1"),
        )
        for (text in texts) {
            assertTrue("Interpret the result" in text, "missing the Interpret section: $text")
            assertTrue("Check soundness" in text, "missing the soundness check: $text")
            assertTrue("Offer a sensible next step" in text, "missing the Offer-next section: $text")
        }
    }

    @Test
    fun `run and optimize guidance nudge toward replications and cost awareness`() {
        val run = KslMcpPrompts.runModelGuidance(tools.availableBundles(), "ksl.examples.mm1", "MM1")
        assertTrue("more replications" in run, "run guidance should nudge toward more replications when imprecise")
        val opt = KslMcpPrompts.optimizeGuidance(emptyList(), "ksl.examples.mm1", "MM1", "System Time")
        assertTrue("preview_optimization_config" in opt, "optimize guidance should preview cost before running")
    }
}
