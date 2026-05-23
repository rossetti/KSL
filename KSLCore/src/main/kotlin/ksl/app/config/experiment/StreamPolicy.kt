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

package ksl.app.config.experiment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 *  Random-stream policy for the design points in an experiment.
 *  Maps to `ksl.controls.experiments.DesignPointRandomStreamPolicy`
 *  at submit time (Phase E2).
 *
 *  The default is [Independent] — each design point starts from a
 *  fresh non-overlapping stream block, so per-point variance
 *  estimates are unbiased.  [CommonRandomNumbers] is explicit
 *  opt-in for the variance-reduction technique that reuses the same
 *  stream block across points; CRN improves cross-point comparisons
 *  but biases per-point variance estimates.
 */
@Serializable
sealed class StreamPolicy {
    /**
     *  Each design point starts from a fresh random-stream block,
     *  with cumulative-replication spacing by default.  The substrate
     *  default.
     *
     *  Advanced knobs ([startingStreamAdvance] and
     *  [streamAdvanceSpacing]) coordinate the stream allocation with
     *  a previous run's RNG state — rarely needed; defaults match
     *  the substrate's cumulative-spacing behaviour.
     */
    @Serializable
    @SerialName("independent")
    data class Independent(
        @TomlComment(
            "Integer >= 0.  Number of substream advances applied\n" +
            "before the first design point's first replication.\n" +
            "Use to align with a previous run's RNG state.  Default 0\n" +
            "= start from the stream's beginning."
        )
        val startingStreamAdvance: Int = 0,

        @TomlComment(
            "Integer >= 1, or omitted.  Per-point substream-advance\n" +
            "spacing.  When omitted (the default), each design point\n" +
            "advances by its predecessor's replication count\n" +
            "(cumulative spacing — points get non-overlapping stream\n" +
            "blocks of exactly the size they need).  Explicit values\n" +
            "force a fixed advance per point regardless of replication\n" +
            "count."
        )
        val streamAdvanceSpacing: Int? = null
    ) : StreamPolicy() {
        init {
            require(startingStreamAdvance >= 0) {
                "startingStreamAdvance must be >= 0; got $startingStreamAdvance"
            }
            require(streamAdvanceSpacing == null || streamAdvanceSpacing >= 1) {
                "streamAdvanceSpacing must be >= 1 when non-null; got $streamAdvanceSpacing"
            }
        }
    }

    /**
     *  All design points start from the same random-stream block —
     *  the classical Common Random Numbers (CRN) variance-reduction
     *  technique.  Reduces variance for cross-point comparisons
     *  (paired differences are correlated, not independent) but
     *  biases per-point variance estimates and inflates per-point
     *  standard errors.
     *
     *  Explicit opt-in only — the document defaults to [Independent].
     */
    @Serializable
    @SerialName("commonRandomNumbers")
    data object CommonRandomNumbers : StreamPolicy()
}
