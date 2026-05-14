package ksl.app.config

import kotlinx.serialization.json.Json
import ksl.controls.experiments.ExperimentRunDefaults
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [ExperimentRunOverrides] focused on:
 *  - per-field validation in the `init` block (bounds are enforced only
 *    when the field is non-null);
 *  - [ExperimentRunOverrides.applyTo] merge semantics (null fields
 *    inherit; non-null fields override);
 *  - round-trip serialization through Kotlinx JSON.
 */
class ExperimentRunOverridesTest {

    private val baseDefaults = ExperimentRunDefaults(
        numberOfReplications = 30,
        numChunks = 1,
        startingRepId = 1,
        lengthOfReplication = 500.0,
        lengthOfReplicationWarmUp = 50.0,
        replicationInitializationOption = true,
        maximumAllowedExecutionTimePerReplication = Duration.ZERO,
        resetStartStreamOption = false,
        advanceNextSubStreamOption = true,
        antitheticOption = false,
        numberOfStreamAdvancesPriorToRunning = 0,
        garbageCollectAfterReplicationFlag = false
    )

    @Test
    fun `empty overrides inherits every default field`() {
        val merged = ExperimentRunOverrides.EMPTY.applyTo(baseDefaults)
        assertEquals(baseDefaults, merged)
    }

    @Test
    fun `EMPTY isEmpty is true`() {
        assertTrue(ExperimentRunOverrides.EMPTY.isEmpty)
    }

    @Test
    fun `setting one field flips isEmpty to false`() {
        val overrides = ExperimentRunOverrides(numberOfReplications = 50)
        assertFalse(overrides.isEmpty)
    }

    @Test
    fun `non-null fields override and null fields inherit`() {
        val overrides = ExperimentRunOverrides(
            numberOfReplications = 50,
            lengthOfReplication = 1000.0,
            antitheticOption = true
        )
        val merged = overrides.applyTo(baseDefaults)
        assertEquals(50, merged.numberOfReplications)
        assertEquals(1000.0, merged.lengthOfReplication)
        assertEquals(true, merged.antitheticOption)
        // Untouched fields inherit
        assertEquals(baseDefaults.numChunks, merged.numChunks)
        assertEquals(baseDefaults.startingRepId, merged.startingRepId)
        assertEquals(baseDefaults.lengthOfReplicationWarmUp, merged.lengthOfReplicationWarmUp)
        assertEquals(baseDefaults.replicationInitializationOption, merged.replicationInitializationOption)
        assertEquals(baseDefaults.resetStartStreamOption, merged.resetStartStreamOption)
        assertEquals(baseDefaults.advanceNextSubStreamOption, merged.advanceNextSubStreamOption)
        assertEquals(baseDefaults.numberOfStreamAdvancesPriorToRunning, merged.numberOfStreamAdvancesPriorToRunning)
        assertEquals(baseDefaults.garbageCollectAfterReplicationFlag, merged.garbageCollectAfterReplicationFlag)
        assertEquals(baseDefaults.maximumAllowedExecutionTimePerReplication, merged.maximumAllowedExecutionTimePerReplication)
    }

    @Test
    fun `all-non-null overrides produce a fully overridden result`() {
        val overrides = ExperimentRunOverrides(
            numberOfReplications = 99,
            numChunks = 3,
            startingRepId = 7,
            lengthOfReplication = 250.5,
            lengthOfReplicationWarmUp = 12.5,
            replicationInitializationOption = false,
            maximumAllowedExecutionTimePerReplication = 5.minutes,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = false,
            antitheticOption = true,
            numberOfStreamAdvancesPriorToRunning = 17,
            garbageCollectAfterReplicationFlag = true
        )
        val merged = overrides.applyTo(baseDefaults)
        assertEquals(99, merged.numberOfReplications)
        assertEquals(3, merged.numChunks)
        assertEquals(7, merged.startingRepId)
        assertEquals(250.5, merged.lengthOfReplication)
        assertEquals(12.5, merged.lengthOfReplicationWarmUp)
        assertEquals(false, merged.replicationInitializationOption)
        assertEquals(5.minutes, merged.maximumAllowedExecutionTimePerReplication)
        assertEquals(true, merged.resetStartStreamOption)
        assertEquals(false, merged.advanceNextSubStreamOption)
        assertEquals(true, merged.antitheticOption)
        assertEquals(17, merged.numberOfStreamAdvancesPriorToRunning)
        assertEquals(true, merged.garbageCollectAfterReplicationFlag)
    }

    @Test
    fun `negative numberOfReplications is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(numberOfReplications = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(numberOfReplications = -1)
        }
    }

    @Test
    fun `zero or negative numChunks is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(numChunks = 0)
        }
    }

    @Test
    fun `zero or negative startingRepId is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(startingRepId = 0)
        }
    }

    @Test
    fun `non-positive lengthOfReplication is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(lengthOfReplication = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(lengthOfReplication = -1.0)
        }
    }

    @Test
    fun `negative lengthOfReplicationWarmUp is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(lengthOfReplicationWarmUp = -0.5)
        }
        // Zero is allowed
        ExperimentRunOverrides(lengthOfReplicationWarmUp = 0.0)
    }

    @Test
    fun `negative numberOfStreamAdvancesPriorToRunning is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExperimentRunOverrides(numberOfStreamAdvancesPriorToRunning = -1)
        }
        // Zero is allowed
        ExperimentRunOverrides(numberOfStreamAdvancesPriorToRunning = 0)
    }

    @Test
    fun `JSON round-trip preserves all fields including Duration`() {
        val original = ExperimentRunOverrides(
            numberOfReplications = 42,
            lengthOfReplication = 100.0,
            maximumAllowedExecutionTimePerReplication = 30.seconds,
            antitheticOption = true
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(ExperimentRunOverrides.serializer(), original)
        val decoded = json.decodeFromString(ExperimentRunOverrides.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `JSON round-trip with all-null overrides is byte-stable`() {
        val original = ExperimentRunOverrides.EMPTY
        val json = Json { encodeDefaults = true }
        val encoded1 = json.encodeToString(ExperimentRunOverrides.serializer(), original)
        val encoded2 = json.encodeToString(ExperimentRunOverrides.serializer(), original)
        assertEquals(encoded1, encoded2)
        val decoded = json.decodeFromString(ExperimentRunOverrides.serializer(), encoded1)
        assertEquals(original, decoded)
    }
}
