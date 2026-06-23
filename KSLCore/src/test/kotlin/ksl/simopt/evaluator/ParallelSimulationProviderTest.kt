package ksl.simopt.evaluator

import kotlinx.coroutines.Dispatchers
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ParallelSimulationProviderTest {

    private companion object {
        const val ARRIVAL_STREAM = 1
        const val SERVICE_STREAM = 2
        const val LENGTH = 200.0
        const val WARMUP = 50.0
        const val REPS = 3
        const val RESPONSE = "System Time"
        const val SERVER_CONTROL = "MM1Q.numServers"   // @set:KSLControl on GIGcQueue named "MM1Q"
    }

    // Each call builds an independent, fresh model (required by the parallel provider). M/M/1
    // with rho = 0.5 (interarrival mean 1.0, service mean 0.5) so System Time is finite/stable.
    private fun buildModel(modelName: String): Model {
        val model = Model(modelName, autoCSVReports = false)
        model.lengthOfReplication = LENGTH
        model.lengthOfReplicationWarmUp = WARMUP
        GIGcQueue(
            model, numServers = 1,
            ad = ExponentialRV(1.0, ARRIVAL_STREAM),
            sd = ExponentialRV(0.5, SERVICE_STREAM),
            name = "MM1Q"
        )
        return model
    }

    private fun modelBuilder(modelName: String): ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = buildModel(modelName)
    }

    private fun point(modelName: String, numServers: Double): ModelInputs =
        ModelInputs(
            modelIdentifier = modelName,
            numReplications = REPS,
            inputs = mapOf(SERVER_CONTROL to numServers),
            responseNames = setOf(RESPONSE)
        )

    private fun Map<ModelInputs, Result<ResponseMap>>.estimate(mi: ModelInputs) =
        getValue(mi).getOrThrow()[RESPONSE]!!

    @Test
    fun parallelMatchesSequentialIndependentStreams() {
        val name = "PSP_Indep_${System.nanoTime()}"
        val p1 = point(name, 1.0)
        val p2 = point(name, 2.0)
        val request = EvaluationRequest(name, listOf(p1, p2), crnOption = false, cachingAllowed = false)

        val sequential = SimulationProvider({ buildModel(name) })
        val parallel = ParallelSimulationProvider(modelBuilder(name))

        val seq = sequential.simulate(request)
        val par = parallel.simulate(request)

        assertEquals(seq.keys, par.keys)
        for (mi in seq.keys) {
            assertEquals(seq.estimate(mi).count, par.estimate(mi).count, "count for $mi")
            assertEquals(seq.estimate(mi).average, par.estimate(mi).average, 1e-10, "average for $mi")
        }
    }

    @Test
    fun parallelMatchesSequentialCommonRandomNumbers() {
        val name = "PSP_CRN_${System.nanoTime()}"
        val p1 = point(name, 1.0)
        val p2 = point(name, 2.0)
        // CRN requires caching disabled and >= 2 points (EvaluationRequest invariants).
        val request = EvaluationRequest(name, listOf(p1, p2), crnOption = true, cachingAllowed = false)

        val sequential = SimulationProvider({ buildModel(name) })
        val parallel = ParallelSimulationProvider(modelBuilder(name))

        val seq = sequential.simulate(request)
        val par = parallel.simulate(request)

        for (mi in seq.keys) {
            assertEquals(seq.estimate(mi).count, par.estimate(mi).count, "count for $mi")
            assertEquals(seq.estimate(mi).average, par.estimate(mi).average, 1e-10, "average for $mi")
        }
    }

    @Test
    fun singlePointShortCircuitMatchesParallelPath() {
        val name = "PSP_SC_${System.nanoTime()}"
        val p = point(name, 1.0)
        val request = EvaluationRequest(name, p, cachingAllowed = false)   // single-point convenience ctor

        val shortCircuit = ParallelSimulationProvider(modelBuilder(name), shortCircuitSinglePoint = true)
        val viaParallel = ParallelSimulationProvider(modelBuilder(name), shortCircuitSinglePoint = false)

        val a = shortCircuit.simulate(request).estimate(p)
        val b = viaParallel.simulate(request).estimate(p)
        assertEquals(a.count, b.count)
        assertEquals(a.average, b.average, 1e-10)
    }

    @Test
    fun parallelIsReproducible() {
        val name = "PSP_Repro_${System.nanoTime()}"
        val request = EvaluationRequest(
            name, listOf(point(name, 1.0), point(name, 2.0)), crnOption = false, cachingAllowed = false
        )
        // Reproducibility = two FRESH providers (each with a fresh stream tape) give identical results
        // for the same request. (Re-running on the SAME provider intentionally draws fresh streams; see
        // parallelAdvancesStreamsAcrossConsecutiveRequests.)
        val p1 = ParallelSimulationProvider(modelBuilder(name))
        val p2 = ParallelSimulationProvider(modelBuilder(name))

        val r1 = p1.simulate(request)
        val r2 = p2.simulate(request)
        for (mi in r1.keys) {
            assertEquals(r1.estimate(mi).average, r2.estimate(mi).average, 1e-12, "reproducible average for $mi")
        }
    }

    @Test
    fun parallelAdvancesStreamsAcrossConsecutiveRequests() {
        val name = "PSP_Advance_${System.nanoTime()}"
        val request = EvaluationRequest(
            name, listOf(point(name, 1.0), point(name, 2.0)), crnOption = false, cachingAllowed = false
        )
        val provider = ParallelSimulationProvider(modelBuilder(name))

        val r1 = provider.simulate(request)
        val r2 = provider.simulate(request)   // same provider: the persistent tape advances => fresh streams
        for (mi in request.modelInputs) {
            assertNotEquals(
                r1.estimate(mi).average, r2.estimate(mi).average,
                "consecutive requests on one provider must draw fresh streams for $mi"
            )
        }
    }

    @Test
    fun resultsAreOrderIndependentAcrossDispatchers() {
        val name = "PSP_Order_${System.nanoTime()}"
        val request = EvaluationRequest(
            name, listOf(point(name, 1.0), point(name, 2.0), point(name, 3.0)),
            crnOption = false, cachingAllowed = false
        )
        val multi = ParallelSimulationProvider(modelBuilder(name))
        val single = ParallelSimulationProvider(modelBuilder(name), dispatcher = Dispatchers.IO.limitedParallelism(1))

        val rMulti = multi.simulate(request)
        val rSingle = single.simulate(request)
        for (mi in rMulti.keys) {
            assertEquals(rMulti.estimate(mi).average, rSingle.estimate(mi).average, 1e-12, "order-independent for $mi")
        }
    }

    /**
     * Reproduces the multi-iteration divergence reported on the LK Inventory model: a solver
     * (e.g. Cross-Entropy) issues many independent multi-point requests in sequence. The
     * sequential provider advances its single model's streams continuously across requests, so
     * each request draws fresh random numbers, and the parallel provider must reproduce that.
     * Phase C's shared StreamTapePolicy makes both providers position each point absolutely from
     * one persistent tape, so they now match request-for-request.
     */
    @Test
    fun parallelMatchesSequentialAcrossConsecutiveRequests() {
        val name = "PSP_MultiReq_${System.nanoTime()}"
        val request = EvaluationRequest(
            name, listOf(point(name, 1.0), point(name, 2.0)), crnOption = false, cachingAllowed = false
        )
        val sequential = SimulationProvider({ buildModel(name) })
        val parallel = ParallelSimulationProvider(modelBuilder(name))

        val seq1 = sequential.simulate(request)
        val seq2 = sequential.simulate(request)   // sequential advances its streams across requests
        val par1 = parallel.simulate(request)
        val par2 = parallel.simulate(request)     // parallel must advance the same way

        for (mi in request.modelInputs) {
            assertEquals(seq1.estimate(mi).average, par1.estimate(mi).average, 1e-10, "request 1 $mi")
            assertNotEquals(
                seq1.estimate(mi).average, seq2.estimate(mi).average,
                "sequential should draw fresh streams on the 2nd request for $mi"
            )
            assertEquals(seq2.estimate(mi).average, par2.estimate(mi).average, 1e-10, "request 2 $mi")
        }
    }

    /**
     * Common random numbers under the unified tape policy: both providers place every point of a
     * request on the same block, then advance the tape by the request's max replications. The
     * parallel provider must therefore match the sequential provider request-for-request, including
     * across consecutive CRN requests.
     */
    @Test
    fun parallelMatchesSequentialCrnAcrossConsecutiveRequests() {
        val name = "PSP_CRNMulti_${System.nanoTime()}"
        val request = EvaluationRequest(
            name, listOf(point(name, 1.0), point(name, 2.0)), crnOption = true, cachingAllowed = false
        )
        val sequential = SimulationProvider({ buildModel(name) })
        val parallel = ParallelSimulationProvider(modelBuilder(name))

        val seq1 = sequential.simulate(request)
        val seq2 = sequential.simulate(request)
        val par1 = parallel.simulate(request)
        val par2 = parallel.simulate(request)

        for (mi in request.modelInputs) {
            assertEquals(seq1.estimate(mi).average, par1.estimate(mi).average, 1e-10, "CRN request 1 $mi")
            assertEquals(seq2.estimate(mi).average, par2.estimate(mi).average, 1e-10, "CRN request 2 $mi")
        }
    }
}
