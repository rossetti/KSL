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

package ksl.app.moda

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 *  The character separating the columns of a delimited file.
 *
 *  Offered as a fixed set rather than as any character, because these are the ones that occur in
 *  practice and naming them keeps a hand-written document readable and free of escaping.
 */
@Serializable
enum class Delimiter(val character: Char) {
    COMMA(','),
    TAB('\t'),
    SEMICOLON(';'),
    PIPE('|')
}

/**
 *  Names a database to read from without carrying the means of getting into it.
 *
 *  Only the name of a connection is recorded. Studies are meant to be shared, committed and
 *  attached to reports, and a document that carried credentials would leak them everywhere it went.
 *  Whoever runs the study supplies the connection that name refers to.
 */
@Serializable
data class DatabaseConnectionRef(
    val connectionName: String
)

/**
 *  Where a study's scores come from.
 *
 *  A study is worth keeping only if it can be re-run, and re-running needs the data as well as the
 *  method, so a document says where its scores came from rather than only what they were. A
 *  recorded result can be read without any of this; reproducing one cannot.
 *
 *  Data entered by hand is held in the document itself, so that a study typed in from a meeting is
 *  complete on its own and does not depend on a file that may not travel with it.
 */
@Serializable
sealed interface ModaSourceReference {

    /** A short description of where the scores come from, for reporting and for error messages. */
    val describe: String

    /**
     *  Scores held in the document, by alternative and then by metric.
     *
     *  For studies entered by hand, where there is no file or database behind the numbers. A
     *  document using this is complete on its own and re-runs anywhere.
     */
    @Serializable
    @SerialName("inline")
    data class InlineScores(
        val table: Map<String, Map<String, Double>>
    ) : ModaSourceReference {
        override val describe: String
            get() = "scores held in the document (${table.size} alternative(s))"
    }

    /**
     *  Scores in a delimited text file, one row per alternative.
     *
     *  A relative [path] is resolved against wherever the document is, so a study and its data can
     *  be moved or committed together.
     */
    @Serializable
    @SerialName("delimitedFile")
    data class DelimitedFile(
        val path: String,
        val alternativeColumn: String,
        val metricColumns: List<String>,
        val delimiter: Delimiter = Delimiter.COMMA
    ) : ModaSourceReference {
        override val describe: String
            get() = "the delimited file '$path'"
    }

    /**
     *  Responses recorded in a KSL database, for scoring simulated alternatives against each other.
     */
    @Serializable
    @SerialName("kslDatabase")
    data class KslDatabase(
        val connection: DatabaseConnectionRef,
        val experiments: List<String>,
        val responses: List<String>
    ) : ModaSourceReference {
        override val describe: String
            get() = "the KSL database connection '${connection.connectionName}'"
    }

    /**
     *  The results of a run that the service is still holding.
     */
    @Serializable
    @SerialName("retainedRun")
    data class RetainedRun(
        val runId: String
    ) : ModaSourceReference {
        override val describe: String
            get() = "the retained run '$runId'"
    }

    /**
     *  Scores from something registered by whoever is running the study, for data this library
     *  knows nothing about.
     */
    @Serializable
    @SerialName("registeredProvider")
    data class RegisteredProvider(
        val providerId: String,
        val parameters: Map<String, String> = emptyMap()
    ) : ModaSourceReference {
        override val describe: String
            get() = "the registered provider '$providerId'"
    }
}
