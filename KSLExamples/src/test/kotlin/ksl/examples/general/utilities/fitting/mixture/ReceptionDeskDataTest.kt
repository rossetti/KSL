/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.utilities.fitting.mixture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 *  The shipped datasets are data files rather than a generator call, which buys the tutorial fixed
 *  numbers but costs it a guarantee: nothing about a text file says where it came from.
 *
 *  These tests supply that guarantee. Each shipped file is compared against the sample its header
 *  claims it is — the true mixture, that stream, that many observations — value for value. That
 *  makes the provenance a checked fact rather than a comment, and it makes the files regenerable
 *  by anyone who needs to, since the recipe is right here and is ordinary public KSL.
 *
 *  A failure here has exactly two causes and they call for opposite responses. Either a file was
 *  edited or replaced, and should be restored; or KSL's random number streams now produce
 *  different values for the same stream number, in which case the files are still perfectly good
 *  data and it is this test's claim about their origin that has expired. Read the failure before
 *  regenerating anything: regenerating changes every number the tutorial quotes.
 */
class ReceptionDeskDataTest {

    /** The recipe the shipped files were produced by, stated once. */
    private fun drawFromTruth(streamNum: Int): DoubleArray =
        receptionDeskTruth.randomVariable(streamNum).sample(RECEPTION_DESK_SIZE)

    @Test
    @DisplayName("The service-time file is the sample its header says it is")
    fun serviceTimesMatchTheTruth() {
        assertSampleMatches(receptionDeskData(), RECEPTION_DESK_STREAM, "ReceptionDeskServiceTimes.txt")
    }

    @Test
    @DisplayName("The hold-out file is the sample its header says it is")
    fun holdOutMatchesTheTruth() {
        assertSampleMatches(receptionDeskHoldOut(), RECEPTION_DESK_HOLD_OUT_STREAM, "ReceptionDeskHoldOut.txt")
    }

    /**
     *  Read, then compare against a fresh draw from the same stream. Exact equality: a Double
     *  written at full precision and scanned back is the same Double, so a tolerance here would
     *  only hide the difference between "this file came from that stream" and "this file is
     *  roughly like something from that stream", which is the whole point of the check.
     */
    private fun assertSampleMatches(fromFile: DoubleArray, streamNum: Int, fileName: String) {
        assertEquals(RECEPTION_DESK_SIZE, fromFile.size) {
            "$fileName holds ${fromFile.size} values, not $RECEPTION_DESK_SIZE"
        }
        val expected = drawFromTruth(streamNum)
        val mismatches = fromFile.indices.filter { fromFile[it] != expected[it] }
        assertTrue(mismatches.isEmpty()) {
            "$fileName does not match stream $streamNum of the true mixture at " +
                "${mismatches.size} of $RECEPTION_DESK_SIZE positions; first at index " +
                "${mismatches.first()}: file has ${fromFile[mismatches.first()]}, " +
                "the stream gives ${expected[mismatches.first()]}"
        }
    }

    /**
     *  The two files must not be the same sample. They are drawn from different streams so that a
     *  fit can be judged on data it never saw; were they identical, every hold-out check in the
     *  tutorial would be measuring nothing and still reporting a number.
     */
    @Test
    @DisplayName("The hold-out sample is a different sample")
    fun theHoldOutIsIndependentOfTheFittingData() {
        assertTrue(!receptionDeskData().contentEquals(receptionDeskHoldOut())) {
            "the two shipped files hold identical data, so nothing is being held out"
        }
    }

    /**
     *  Service times are positive. The truth's first component is a Normal with mean 3 and
     *  variance 0.36, which admits negative values in principle at about eight standard
     *  deviations; this records that the shipped sample contains none, so an example may present
     *  these as service times without qualification.
     */
    @Test
    @DisplayName("Every shipped observation is a usable service time")
    fun everyObservationIsPositiveAndFinite() {
        for ((name, data) in listOf("service times" to receptionDeskData(), "hold-out" to receptionDeskHoldOut())) {
            val bad = data.filter { !it.isFinite() || it <= 0.0 }
            assertTrue(bad.isEmpty()) { "$name contains ${bad.size} non-positive or non-finite values: ${bad.take(5)}" }
        }
    }
}
