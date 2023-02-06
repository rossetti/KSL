/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.io.dbutil

import org.ktorm.dsl.isNotNull
import org.ktorm.schema.*

open class SchemaName(val schemaName: String? = null)

object KSLDBSchema : SchemaName("KSL_DB") {

    object DbExperiments : Table<KSLDatabase.DbExperiment>(tableName = "EXPERIMENT", schema = schemaName) {
        var expId = int("EXP_ID").primaryKey().bindTo { it.expId }
        var simName = varchar("SIM_NAME").bindTo { it.simName }.isNotNull()
        var modelName = varchar("MODEL_NAME").bindTo { it.modelName }.isNotNull()
        var expName = varchar("EXP_NAME").bindTo { it.expName }.isNotNull()
        var numReps = int("NUM_REPS").bindTo { it.numReps }.isNotNull()
        var isChunked = boolean("IS_CHUNKED").bindTo { it.isChunked }.isNotNull()
        var lengthOfRep = double("LENGTH_OF_REP").bindTo { it.lengthOfRep }
        var lengthOfWarmUp = double("LENGTH_OF_WARM_UP").bindTo { it.lengthOfWarmUp }
        var repAllowedExecTime = long("REP_ALLOWED_EXEC_TIME").bindTo { it.repAllowedExecTime }
        var repInitOption = boolean("REP_INIT_OPTION").bindTo { it.repInitOption }
        var repResetStartStreamOption = boolean("RESET_START_STREAM_OPTION").bindTo { it.repResetStartStreamOption }
        var antitheticOption = boolean("ANTITHETIC_OPTION").bindTo { it.antitheticOption }
        var advNextSubStreamOption = boolean("ADV_NEXT_SUB_STREAM_OPTION").bindTo { it.advNextSubStreamOption }
        var numStreamAdvances = int("NUM_STREAM_ADVANCES").bindTo { it.numStreamAdvances }
        var gcAfterRepOption = boolean("GC_AFTER_REP_OPTION").bindTo { it.gcAfterRepOption }
    }

    object SimulationRuns : Table<KSLDatabase.SimulationRun>(tableName = "SIMULATION_RUN", schema = schemaName) {
        var runId = int("RUN_ID").primaryKey().bindTo { it.runId }.isNotNull()
        var expIdFk = int("EXP_ID_FK").bindTo { it.expIDFk }.isNotNull()
        var runName = varchar("RUN_NAME").bindTo { it.runName }.isNotNull()
        var numReps = int("NUM_REPS").bindTo { it.numReps }.isNotNull()
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var runStartTimeStamp = timestamp("RUN_START_TIME_STAMP").bindTo { it.runStartTimeStamp }
        var runEndTimeStamp = timestamp("RUN_END_TIME_STAMP").bindTo { it.runEndTimeStamp }
        var runErrorMsg = varchar("RUN_ERROR_MSG").bindTo { it.runErrorMsg }
    }

    object DbModelElements : Table<KSLDatabase.DbModelElement>(tableName = "MODEL_ELEMENT", schema = schemaName) {
        var expIDFk = int("EXP_ID_FK").primaryKey().bindTo { it.expIDFk } //not sure how to do references
        var elementId = int("ELEMENT_ID").primaryKey().bindTo { it.elementId }
        var elementName = varchar("ELEMENT_NAME").bindTo { it.elementName }.isNotNull()
        var elementClassName = varchar("CLASS_NAME").bindTo { it.elementClassName }.isNotNull()
        var parentIDFk = int("PARENT_ID_FK").bindTo { it.parentIDFk }
        var parentName = varchar("PARENT_NAME").bindTo { it.parentName }
        var leftCount = int("LEFT_COUNT").bindTo { it.leftCount }
        var rightCount = int("RIGHT_COUNT").bindTo { it.rightCount }
    }

    object DbControls : Table<KSLDatabase.DbControl>(tableName = "CONTROL", schema = schemaName) {
        var controlId = int("CONTROL_ID").primaryKey().bindTo { it.controlId }
        var expIDFk = int("EXP_ID_FK").bindTo { it.expIdFk }.isNotNull() //not sure how to do references
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk }.isNotNull()
        var keyName = varchar("KEY_NAME").bindTo { it.keyName }.isNotNull()
        var controlValue = double("CONTROL_VALUE").bindTo { it.controlValue }.isNotNull()
        var lowerBound = double("LOWER_BOUND").bindTo { it.lowerBound }
        var upperBound = double("UPPER_BOUND").bindTo { it.upperBound }
        var propertyName = varchar("PROPERTY_NAME").bindTo { it.propertyName }.isNotNull()
        var controlType = varchar("CONTROL_TYPE").bindTo { it.controlType }.isNotNull()
        var comment = varchar("COMMENT").bindTo { it.comment }
    }

    object DbRvParameters : Table<KSLDatabase.DbRvParameter>(tableName = "RV_PARAMETER", schema = schemaName) {
        var rvParamId = int("RV_PARAM_ID").primaryKey().bindTo { it.rvParamId }
        var expIDFk = int("EXP_ID_FK").bindTo { it.expIDFk }.isNotNull() //not sure how to do references
        var elementId = int("ELEMENT_ID_FK").bindTo { it.elementIdFk }.isNotNull()
        var clazzName = varchar("CLASS_NAME").bindTo { it.clazzName }.isNotNull()
        var dataType = varchar("DATA_TYPE").bindTo { it.dataType }.isNotNull()
        var rvName = varchar("RV_NAME").bindTo { it.rvName }.isNotNull()
        var paramName = varchar("PARAM_NAME").bindTo { it.paramName }.isNotNull()
        var paramValue = double("PARAM_VALUE").bindTo { it.paramValue }.isNotNull()
    }

    object WithinRepStats : Table<KSLDatabase.WithinRepStat>(tableName = "WITHIN_REP_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var repId = int("REP_ID").bindTo { it.repId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var weightedSum = double("WEIGHTED_SUM").bindTo { it.weightedSum }
        var sumOfWeights = double("SUM_OF_WEIGHTS").bindTo { it.sumOfWeights }
        var weightedSSQ = double("WEIGHTED_SSQ").bindTo { it.weightedSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var lastWeight = double("LAST_WEIGHT").bindTo { it.lastWeight }
    }

    object AcrossRepStats : Table<KSLDatabase.AcrossRepStat>(tableName = "ACROSS_REP_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
        var stdError = double("STD_ERR").bindTo { it.stdError }
        var halfWidth = double("HALF_WIDTH").bindTo { it.halfWidth }
        var confLevel = double("CONF_LEVEL").bindTo { it.confLevel }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var sumOfObs = double("SUM_OF_OBS").bindTo { it.sumOfObs }
        var devSSQ = double("DEV_SSQ").bindTo { it.devSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var kurtosis = double("KURTOSIS").bindTo { it.kurtosis }
        var skewness = double("SKEWNESS").bindTo { it.skewness }
        var lag1Cov = double("LAG1_COV").bindTo { it.lag1Cov }
        var lag1Corr = double("LAG1_CORR").bindTo { it.lag1Corr }
        var vonNeumannLag1Stat = double("VON_NEUMANN_LAG1_STAT").bindTo { it.vonNeumannLag1Stat }
        var numMissingObs = double("NUM_MISSING_OBS").bindTo { it.numMissingObs }
    }

    object WithinRepCounterStats : Table<KSLDatabase.WithinRepCounterStat>(tableName = "WITHIN_REP_COUNTER_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var repId = int("REP_ID").bindTo { it.repId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
    }

    object BatchStats : Table<KSLDatabase.BatchStat>(tableName = "BATCH_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var repId = int("REP_ID").bindTo { it.repId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
        var stdError = double("STD_ERR").bindTo { it.stdError }
        var halfWidth = double("HALF_WIDTH").bindTo { it.halfWidth }
        var confLevel = double("CONF_LEVEL").bindTo { it.confLevel }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var sumOfObs = double("SUM_OF_OBS").bindTo { it.sumOfObs }
        var devSSQ = double("DEV_SSQ").bindTo { it.devSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var kurtosis = double("KURTOSIS").bindTo { it.kurtosis }
        var skewness = double("SKEWNESS").bindTo { it.skewness }
        var lag1Cov = double("LAG1_COV").bindTo { it.lag1Cov }
        var lag1Corr = double("LAG1_CORR").bindTo { it.lag1Corr }
        var vonNeumannLag1Stat = double("VON_NEUMANN_LAG1_STAT").bindTo { it.vonNeumannLag1Stat }
        var numMissingObs = double("NUM_MISSING_OBS").bindTo { it.numMissingObs }
        var minBatchSize = double("MIN_BATCH_SIZE").bindTo { it.minBatchSize }
        var minNumBatches = double("MIN_NUM_BATCHES").bindTo { it.minNumBatches }
        var maxNumBatchesMultiple = double("MAX_NUM_BATCHES_MULTIPLE").bindTo { it.maxNumBatchesMultiple }
        var maxNumBatches = double("MAX_NUM_BATCHES").bindTo { it.maxNumBatches }
        var numRebatches = double("NUM_REBATCHES").bindTo { it.numRebatches }
        var currentBatchSize = double("CURRENT_BATCH_SIZE").bindTo { it.currentBatchSize }
        var amtUnbatched = double("AMT_UNBATCHED").bindTo { it.amtUnbatched }
        var totalNumObs = double("TOTAL_NUM_OBS").bindTo { it.totalNumObs }
    }

    object WithinRepResponseViewStats : Table<KSLDatabase.WithinRepResponseView>(tableName = "WITHIN_REP_RESPONSE_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var numReps = int("NUM_REPS").bindTo { it.expNumReps }
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var average = double("AVERAGE").bindTo { it.average }
    }

    object WithinRepCounterViewStats : Table<KSLDatabase.WithinRepCounterView>(tableName = "WITHIN_REP_COUNTER_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var numReps = int("NUM_REPS").bindTo { it.expNumReps }
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
    }

    object WithinRepViewStats : Table<KSLDatabase.WithinRepView>(tableName = "WITHIN_REP_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var numReps = int("NUM_REPS").bindTo { it.expNumReps }
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var repValue = double("REP_VALUE").bindTo { it.repValue }
    }

    object ExpStatRepViewStats : Table<KSLDatabase.ExpStatRepView>(tableName = "EXP_STAT_REP_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var repValue = double("REP_VALUE").bindTo { it.repValue }
    }

    object PairWiseDiffViewStats : Table<KSLDatabase.PairWiseDiffView>(tableName = "PW_DIFF_WITHIN_REP_VIEW", schema = schemaName) {
        var simName = varchar("SIM_NAME").bindTo { it.simName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var expNameA = varchar("A_EXP_NAME").bindTo { it.expNameA }
        var valueA = double("A_VALUE").bindTo { it.valueA }
        var expNameB = varchar("B_EXP_NAME").bindTo { it.expNameB }
        var valueB = double("B_VALUE").bindTo { it.valueB }
        var diffName = varchar("DIFF_NAME").bindTo { it.diffName }
        var AminusB = double("A_MINUS_B").bindTo { it.AminusB }
    }

    object AcrossRepViewStats : Table<KSLDatabase.AcrossRepView>(tableName = "ACROSS_REP_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
    }

    object BatchViewStats : Table<KSLDatabase.BatchStatView>(tableName = "BATCH_STAT_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
    }
}

object KSLDBSchemaSQLite : SchemaName() {

    object DbExperiments : Table<KSLDatabase.DbExperiment>(tableName = "EXPERIMENT", schema = schemaName) {
        var expId = int("EXP_ID").primaryKey().bindTo { it.expId }
        var simName = varchar("SIM_NAME").bindTo { it.simName }.isNotNull()
        var modelName = varchar("MODEL_NAME").bindTo { it.modelName }.isNotNull()
        var expName = varchar("EXP_NAME").bindTo { it.expName }.isNotNull()
        var numReps = int("NUM_REPS").bindTo { it.numReps }.isNotNull()
        var isChunked = boolean("IS_CHUNKED").bindTo { it.isChunked }.isNotNull()
        var lengthOfRep = double("LENGTH_OF_REP").bindTo { it.lengthOfRep }
        var lengthOfWarmUp = double("LENGTH_OF_WARM_UP").bindTo { it.lengthOfWarmUp }
        var repAllowedExecTime = long("REP_ALLOWED_EXEC_TIME").bindTo { it.repAllowedExecTime }
        var repInitOption = boolean("REP_INIT_OPTION").bindTo { it.repInitOption }
        var repResetStartStreamOption = boolean("RESET_START_STREAM_OPTION").bindTo { it.repResetStartStreamOption }
        var antitheticOption = boolean("ANTITHETIC_OPTION").bindTo { it.antitheticOption }
        var advNextSubStreamOption = boolean("ADV_NEXT_SUB_STREAM_OPTION").bindTo { it.advNextSubStreamOption }
        var numStreamAdvances = int("NUM_STREAM_ADVANCES").bindTo { it.numStreamAdvances }
        var gcAfterRepOption = boolean("GC_AFTER_REP_OPTION").bindTo { it.gcAfterRepOption }
    }

    object SimulationRuns : Table<KSLDatabase.SimulationRun>(tableName = "SIMULATION_RUN", schema = schemaName) {
        var runId = int("RUN_ID").primaryKey().bindTo { it.runId }.isNotNull()
        var expIdFk = int("EXP_ID_FK").bindTo { it.expIDFk }.isNotNull()
        var runName = varchar("RUN_NAME").bindTo { it.runName }.isNotNull()
        var numReps = int("NUM_REPS").bindTo { it.numReps }.isNotNull()
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var runStartTimeStamp = timestamp("RUN_START_TIME_STAMP").bindTo { it.runStartTimeStamp }
        var runEndTimeStamp = timestamp("RUN_END_TIME_STAMP").bindTo { it.runEndTimeStamp }
        var runErrorMsg = varchar("RUN_ERROR_MSG").bindTo { it.runErrorMsg }
    }

    object DbModelElements : Table<KSLDatabase.DbModelElement>(tableName = "MODEL_ELEMENT", schema = schemaName) {
        var expIDFk = int("EXP_ID_FK").primaryKey().bindTo { it.expIDFk } //not sure how to do references
        var elementId = int("ELEMENT_ID").primaryKey().bindTo { it.elementId }
        var elementName = varchar("ELEMENT_NAME").bindTo { it.elementName }.isNotNull()
        var elementClassName = varchar("CLASS_NAME").bindTo { it.elementClassName }.isNotNull()
        var parentIDFk = int("PARENT_ID_FK").bindTo { it.parentIDFk }
        var parentName = varchar("PARENT_NAME").bindTo { it.parentName }
        var leftCount = int("LEFT_COUNT").bindTo { it.leftCount }
        var rightCount = int("RIGHT_COUNT").bindTo { it.rightCount }
    }

    object DbControls : Table<KSLDatabase.DbControl>(tableName = "CONTROL", schema = schemaName) {
        var controlId = int("CONTROL_ID").primaryKey().bindTo { it.controlId }
        var expIDFk = int("EXP_ID_FK").bindTo { it.expIdFk }.isNotNull() //not sure how to do references
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk }.isNotNull()
        var keyName = varchar("KEY_NAME").bindTo { it.keyName }.isNotNull()
        var controlValue = double("CONTROL_VALUE").bindTo { it.controlValue }.isNotNull()
        var lowerBound = double("LOWER_BOUND").bindTo { it.lowerBound }
        var upperBound = double("UPPER_BOUND").bindTo { it.upperBound }
        var propertyName = varchar("PROPERTY_NAME").bindTo { it.propertyName }.isNotNull()
        var controlType = varchar("CONTROL_TYPE").bindTo { it.controlType }.isNotNull()
        var comment = varchar("COMMENT").bindTo { it.comment }
    }

    object DbRvParameters : Table<KSLDatabase.DbRvParameter>(tableName = "RV_PARAMETER", schema = schemaName) {
        var rvParamId = int("RV_PARAM_ID").primaryKey().bindTo { it.rvParamId }
        var expIDFk = int("EXP_ID_FK").bindTo { it.expIDFk }.isNotNull() //not sure how to do references
        var elementId = int("ELEMENT_ID_FK").bindTo { it.elementIdFk }.isNotNull()
        var clazzName = varchar("CLASS_NAME").bindTo { it.clazzName }.isNotNull()
        var dataType = varchar("DATA_TYPE").bindTo { it.dataType }.isNotNull()
        var rvName = varchar("RV_NAME").bindTo { it.rvName }.isNotNull()
        var paramName = varchar("PARAM_NAME").bindTo { it.paramName }.isNotNull()
        var paramValue = double("PARAM_VALUE").bindTo { it.paramValue }.isNotNull()
    }

    object WithinRepStats : Table<KSLDatabase.WithinRepStat>(tableName = "WITHIN_REP_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var repId = int("REP_ID").bindTo { it.repId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var weightedSum = double("WEIGHTED_SUM").bindTo { it.weightedSum }
        var sumOfWeights = double("SUM_OF_WEIGHTS").bindTo { it.sumOfWeights }
        var weightedSSQ = double("WEIGHTED_SSQ").bindTo { it.weightedSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var lastWeight = double("LAST_WEIGHT").bindTo { it.lastWeight }
    }

    object AcrossRepStats : Table<KSLDatabase.AcrossRepStat>(tableName = "ACROSS_REP_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
        var stdError = double("STD_ERR").bindTo { it.stdError }
        var halfWidth = double("HALF_WIDTH").bindTo { it.halfWidth }
        var confLevel = double("CONF_LEVEL").bindTo { it.confLevel }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var sumOfObs = double("SUM_OF_OBS").bindTo { it.sumOfObs }
        var devSSQ = double("DEV_SSQ").bindTo { it.devSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var kurtosis = double("KURTOSIS").bindTo { it.kurtosis }
        var skewness = double("SKEWNESS").bindTo { it.skewness }
        var lag1Cov = double("LAG1_COV").bindTo { it.lag1Cov }
        var lag1Corr = double("LAG1_CORR").bindTo { it.lag1Corr }
        var vonNeumannLag1Stat = double("VON_NEUMANN_LAG1_STAT").bindTo { it.vonNeumannLag1Stat }
        var numMissingObs = double("NUM_MISSING_OBS").bindTo { it.numMissingObs }
    }

    object WithinRepCounterStats : Table<KSLDatabase.WithinRepCounterStat>(tableName = "WITHIN_REP_COUNTER_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var repId = int("REP_ID").bindTo { it.repId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
    }

    object BatchStats : Table<KSLDatabase.BatchStat>(tableName = "BATCH_STAT", schema = schemaName) {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //not sure how to do references
        var repId = int("REP_ID").bindTo { it.repId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
        var stdError = double("STD_ERR").bindTo { it.stdError }
        var halfWidth = double("HALF_WIDTH").bindTo { it.halfWidth }
        var confLevel = double("CONF_LEVEL").bindTo { it.confLevel }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var sumOfObs = double("SUM_OF_OBS").bindTo { it.sumOfObs }
        var devSSQ = double("DEV_SSQ").bindTo { it.devSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var kurtosis = double("KURTOSIS").bindTo { it.kurtosis }
        var skewness = double("SKEWNESS").bindTo { it.skewness }
        var lag1Cov = double("LAG1_COV").bindTo { it.lag1Cov }
        var lag1Corr = double("LAG1_CORR").bindTo { it.lag1Corr }
        var vonNeumannLag1Stat = double("VON_NEUMANN_LAG1_STAT").bindTo { it.vonNeumannLag1Stat }
        var numMissingObs = double("NUM_MISSING_OBS").bindTo { it.numMissingObs }
        var minBatchSize = double("MIN_BATCH_SIZE").bindTo { it.minBatchSize }
        var minNumBatches = double("MIN_NUM_BATCHES").bindTo { it.minNumBatches }
        var maxNumBatchesMultiple = double("MAX_NUM_BATCHES_MULTIPLE").bindTo { it.maxNumBatchesMultiple }
        var maxNumBatches = double("MAX_NUM_BATCHES").bindTo { it.maxNumBatches }
        var numRebatches = double("NUM_REBATCHES").bindTo { it.numRebatches }
        var currentBatchSize = double("CURRENT_BATCH_SIZE").bindTo { it.currentBatchSize }
        var amtUnbatched = double("AMT_UNBATCHED").bindTo { it.amtUnbatched }
        var totalNumObs = double("TOTAL_NUM_OBS").bindTo { it.totalNumObs }
    }

    object WithinRepResponseViewStats : Table<KSLDatabase.WithinRepResponseView>(tableName = "WITHIN_REP_RESPONSE_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var numReps = int("NUM_REPS").bindTo { it.expNumReps }
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var average = double("AVERAGE").bindTo { it.average }
    }

    object WithinRepCounterViewStats : Table<KSLDatabase.WithinRepCounterView>(tableName = "WITHIN_REP_COUNTER_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var numReps = int("NUM_REPS").bindTo { it.expNumReps }
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
    }

    object WithinRepViewStats : Table<KSLDatabase.WithinRepView>(tableName = "WITHIN_REP_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var numReps = int("NUM_REPS").bindTo { it.expNumReps }
        var startRepId = int("START_REP_ID").bindTo { it.startRepId }
        var lastRepId = int("LAST_REP_ID").bindTo { it.lastRepId }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var repValue = double("REP_VALUE").bindTo { it.repValue }
    }

    object ExpStatRepViewStats : Table<KSLDatabase.ExpStatRepView>(tableName = "EXP_STAT_REP_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var repValue = double("REP_VALUE").bindTo { it.repValue }
    }

    object PairWiseDiffViewStats : Table<KSLDatabase.PairWiseDiffView>(tableName = "PW_DIFF_WITHIN_REP_VIEW", schema = schemaName) {
        var simName = varchar("SIM_NAME").bindTo { it.simName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var repId = int("REP_ID").bindTo { it.repId }
        var expNameA = varchar("A_EXP_NAME").bindTo { it.expNameA }
        var valueA = double("A_VALUE").bindTo { it.valueA }
        var expNameB = varchar("B_EXP_NAME").bindTo { it.expNameB }
        var valueB = double("B_VALUE").bindTo { it.valueB }
        var diffName = varchar("DIFF_NAME").bindTo { it.diffName }
        var AminusB = double("A_MINUS_B").bindTo { it.AminusB }
    }

    object AcrossRepViewStats : Table<KSLDatabase.AcrossRepView>(tableName = "ACROSS_REP_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
    }

    object BatchViewStats : Table<KSLDatabase.BatchStatView>(tableName = "BATCH_STAT_VIEW", schema = schemaName) {
        var expName = varchar("EXP_NAME").bindTo { it.expName }
        var runName = varchar("RUN_NAME").bindTo { it.runName }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
    }
}

fun main(){
    println("KSLDBSchema = ${KSLDBSchema.AcrossRepStats}")

    println("KSLDBSchemaSQLite = ${KSLDBSchemaSQLite.AcrossRepStats}")
    
    val x: KSLDBSchema.AcrossRepStats = KSLDBSchema.AcrossRepStats
    
}