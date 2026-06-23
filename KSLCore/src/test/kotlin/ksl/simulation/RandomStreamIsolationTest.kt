package ksl.simulation

import ksl.modeling.variable.RandomVariable
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ExponentialRV as ExponentialSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RandomStreamIsolationTest {

    private class StreamProbe(
        parent: ModelElement,
        name: String = "StreamProbe"
    ) : ModelElement(parent, name) {

        val owningStreamProvider: RNStreamProvider
            get() = streamProvider

        fun exponentialViaFactory(streamNum: Int = 0): ExponentialSource {
            return ExponentialRV(
                mean = 1.0,
                streamNum = streamNum,
                name = "FactoryExponential"
            )
        }

        fun randomVariableFromFactory(
            streamNum: Int = 0,
            rvName: String = "ProbeRV"
        ): RandomVariable {
            return RandomVariable(
                this,
                ExponentialRV(
                    mean = 1.0,
                    streamNum = streamNum,
                    name = "${rvName}:Source"
                ),
                name = rvName
            )
        }

        fun randomVariableFromExternalSource(
            source: ExponentialSource,
            rvName: String = "ExternalProbeRV"
        ): RandomVariable {
            return RandomVariable(this, source, name = rvName)
        }
    }

    @Test
    fun modelElementFactoryRVsUseOwningModelStreamProvider() {
        val model1 = Model("FactoryProviderModel1", autoCSVReports = false)
        val model2 = Model("FactoryProviderModel2", autoCSVReports = false)

        val probe1 = StreamProbe(model1, "Probe1")
        val probe2 = StreamProbe(model2, "Probe2")

        val rv1 = probe1.exponentialViaFactory(streamNum = 7)
        val rv2 = probe2.exponentialViaFactory(streamNum = 7)

        assertSame(probe1.owningStreamProvider, rv1.streamProvider)
        assertSame(probe2.owningStreamProvider, rv2.streamProvider)
        assertNotSame(probe1.owningStreamProvider, probe2.owningStreamProvider)

        assertEquals(7, rv1.streamNumber)
        assertEquals(7, rv2.streamNumber)
    }

    @Test
    fun sameStreamNumberInDifferentModelProvidersProducesSameSequenceWithoutSharedState() {
        val model1 = Model("CRNProviderModel1", autoCSVReports = false)
        val model2 = Model("CRNProviderModel2", autoCSVReports = false)

        val probe1 = StreamProbe(model1, "CRNProbe1")
        val probe2 = StreamProbe(model2, "CRNProbe2")

        val rv1 = probe1.exponentialViaFactory(streamNum = 11)
        val rv2 = probe2.exponentialViaFactory(streamNum = 11)

        assertNotSame(rv1.streamProvider, rv2.streamProvider)

        val rv1First = rv1.value
        val rv1Second = rv1.value

        val rv2First = rv2.value
        val rv2Second = rv2.value

        assertEquals(rv1First, rv2First, 1.0e-12)
        assertEquals(rv1Second, rv2Second, 1.0e-12)
    }

    @Test
    fun randomVariableClonesExternalSourceIntoModelProvider() {
        val model = Model("ExternalSourceCloneModel", autoCSVReports = false)
        val probe = StreamProbe(model)

        val externalProvider = RNStreamProvider(name = "ExternalProvider")
        val externalSource = ExponentialSource(
            mean = 2.0,
            streamNum = 17,
            streamProvider = externalProvider,
            name = "ExternalSource"
        )

        val rv = probe.randomVariableFromExternalSource(externalSource)

        assertNotSame(externalProvider, rv.initialRandomSource.streamProvider)
        assertNotSame(externalSource, rv.initialRandomSource)

        assertSame(probe.owningStreamProvider, rv.initialRandomSource.streamProvider)
        assertSame(probe.owningStreamProvider, rv.randomSource.streamProvider)
        assertEquals(17, rv.streamNumber)
    }

    @Test
    fun randomVariableInitialSourceReplacementAlsoClonesExternalProvider() {
        val model = Model("InitialSourceReplacementModel", autoCSVReports = false)
        val probe = StreamProbe(model)
        val rv = probe.randomVariableFromFactory(streamNum = 3)

        val externalProvider = RNStreamProvider(name = "ReplacementExternalProvider")
        val externalSource = ExponentialSource(
            mean = 3.0,
            streamNum = 19,
            streamProvider = externalProvider,
            name = "ReplacementExternalSource"
        )

        rv.initialRandomSource = externalSource

        assertNotSame(externalProvider, rv.initialRandomSource.streamProvider)
        assertNotSame(externalSource, rv.initialRandomSource)

        assertSame(probe.owningStreamProvider, rv.initialRandomSource.streamProvider)
        assertEquals(19, rv.streamNumber)
    }

    @Test
    fun rvParameterSetterPreservesModelProviderAndStreamNumber() {
        val model = Model("RVParameterSetterProviderModel", autoCSVReports = false)
        val probe = StreamProbe(model)
        val rv = probe.randomVariableFromFactory(
            streamNum = 23,
            rvName = "ParameterizedProbeRV"
        )

        val setter = model.rvParameterSetter

        assertTrue(setter.changeParameter("ParameterizedProbeRV", "mean", 4.0))

        val changedCount = setter.applyParameterChanges(model)

        assertEquals(1, changedCount)
        assertSame(probe.owningStreamProvider, rv.initialRandomSource.streamProvider)
        assertEquals(23, rv.streamNumber)

        val changedSource = rv.initialRandomSource
        assertTrue(changedSource is ExponentialSource)
        assertEquals(4.0, (changedSource as ExponentialSource).mean, 1.0e-10)
    }
}
