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

package ksl.controls.experiments

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import ksl.simulation.ExperimentRunParametersIfc
import kotlin.time.Duration

/**
 *  Holds data about the run parameters of a simulation experiment.
 */
@kotlinx.serialization.Serializable
data class ExperimentRunParameters(
    override var experimentName: String,
    override val experimentId: Int,
    override var numberOfReplications: Int,
    override var isChunked: Boolean,
    override var chunkLabel: String,
    override var startingRepId: Int,
    override var lengthOfReplication: Double,
    override var lengthOfReplicationWarmUp: Double,
    override var replicationInitializationOption: Boolean,
    @Serializable(with = DurationSerializer::class)
    override var maximumAllowedExecutionTimePerReplication: Duration,
    override var resetStartStreamOption: Boolean,
    override var advanceNextSubStreamOption: Boolean,
    override var antitheticOption: Boolean,
    override var numberOfStreamAdvancesPriorToRunning: Int,
    override var garbageCollectAfterReplicationFlag: Boolean
) : ExperimentRunParametersIfc {

    init {
        require(startingRepId >= 1) { "Starting replication number must be >= 1" }
        require(lengthOfReplication > 0.0) { "Length of replication must be > 0.0" }
        require(lengthOfReplicationWarmUp >= 0.0) { "Length of warm up period must be >= 0.0" }
        require(numberOfReplications >= 1) { "Number of replications must be >= 1" }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("ExperimentRunParameters")
        sb.appendLine("experimentName='$experimentName'")
        sb.appendLine("experimentId=$experimentId")
        sb.appendLine("numberOfReplications=$numberOfReplications")
        sb.appendLine("isChunked=$isChunked")
        sb.appendLine("chunkLabel='$chunkLabel'")
        sb.appendLine("startingRepId=$startingRepId")
        sb.appendLine("lengthOfReplication=$lengthOfReplication,")
        sb.appendLine("lengthOfReplicationWarmUp=$lengthOfReplicationWarmUp")
        sb.appendLine("replicationInitializationOption=$replicationInitializationOption")
        sb.appendLine("maximumAllowedExecutionTimePerReplication=$maximumAllowedExecutionTimePerReplication")
        sb.appendLine("resetStartStreamOption=$resetStartStreamOption")
        sb.appendLine("advanceNextSubStreamOption=$advanceNextSubStreamOption")
        sb.appendLine("antitheticOption=$antitheticOption")
        sb.appendLine("numberOfStreamAdvancesPriorToRunning=$numberOfStreamAdvancesPriorToRunning")
        sb.appendLine("garbageCollectAfterReplicationFlag=$garbageCollectAfterReplicationFlag)")
        return sb.toString()
    }

    fun toJson(): String{
        val format = Json { prettyPrint = true }
        return format.encodeToString(this)
    }
}


object DurationSerializer : KSerializer<Duration> {

    private val serializer = Duration.serializer()

    override val descriptor: SerialDescriptor = serializer.descriptor

    override fun deserialize(decoder: Decoder): Duration =
        decoder.decodeSerializableValue(serializer)

    override fun serialize(encoder: Encoder, value: Duration) {
        encoder.encodeSerializableValue(serializer, value)
    }
}