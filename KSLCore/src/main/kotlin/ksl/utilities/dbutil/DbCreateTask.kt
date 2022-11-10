/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.dbutil

import ksl.utilities.exceptions.DataAccessException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * A DbCreateTask represents a set of instructions that can be used to create, possibly fill,
 * and alter a database. It can be used only once. The enum Type indicates what kind of
 * tasks will be executed and the state of the task.
 */
class DbCreateTask private constructor(builder: DbCreateTaskBuilder) {
    enum class Type {
        NONE, FULL_SCRIPT, TABLES, TABLES_INSERT, TABLES_ALTER, TABLES_EXCEL, TABLES_INSERT_ALTER, TABLES_EXCEL_ALTER
    }

    enum class State {
        UN_EXECUTED, EXECUTED, EXECUTION_ERROR, NO_TABLES_ERROR
    }

    /**
     *
     * @return the path to the Excel workbook to be used for inserting data, may be null
     */
    var excelWorkbookPathForDataInsert: Path? = null
    private var pathToCreationScript: Path? = null
    private var pathToTablesScript: Path? = null
    private var pathToInsertScript: Path? = null
    private var pathToAlterScript: Path? = null
    private var myInsertTableOrder: List<String> = ArrayList()
    private var myCreationScriptCommands: List<String> = ArrayList()
    private var myTableCommands: List<String> = ArrayList()
    private var myInsertCommands: List<String> = ArrayList()
    private var myAlterCommands: List<String> = ArrayList()

    /**
     * @return the type of the command sequence specified during the builder process
     */
    var type: Type
    var state = State.UN_EXECUTED
        private set
    private val myDatabase: DatabaseIfc

    /**
     *
     * @return a list of table names in the order in which they need to be inserted. May be empty
     */
    val insertTableOrder: List<String>
        get() = Collections.unmodifiableList(myInsertTableOrder)

    /**
     *
     * @return a list of all the commands that were in the creation script, may be empty
     */
    val creationScriptCommands: List<String>
        get() = Collections.unmodifiableList(myCreationScriptCommands)

    /**
     *
     * @return a list of the create table commands, may be empty
     */
    val tableCommands: List<String>
        get() = Collections.unmodifiableList(myTableCommands)

    /**
     *
     * @return a list of the insert commands, may be empty
     */
    val insertCommands: List<String>
        get() = Collections.unmodifiableList(myInsertCommands)

    /**
     *
     * @return a list of the alter commands, may be empty
     */
    val alterCommands: List<String>
        get() = Collections.unmodifiableList(myAlterCommands)

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("DbCreateTask{")
        sb.append(System.lineSeparator())
        sb.append("type=").append(type)
        sb.append(System.lineSeparator())
        sb.append("state=").append(state)
        sb.append(System.lineSeparator())
        sb.append("Creation script=").append(pathToCreationScript)
        sb.append(System.lineSeparator())
        sb.append("Full Creation Script Commands=").append(!myCreationScriptCommands.isEmpty())
        sb.append(System.lineSeparator())
        sb.append("Tables script=").append(pathToTablesScript)
        sb.append(System.lineSeparator())
        sb.append("Table Commands=").append(!myTableCommands.isEmpty())
        sb.append(System.lineSeparator())
        sb.append("Insert script=").append(pathToInsertScript)
        sb.append(System.lineSeparator())
        sb.append("Insert Commands=").append(!myInsertCommands.isEmpty())
        sb.append(System.lineSeparator())
        sb.append("Alter script=").append(pathToAlterScript)
        sb.append(System.lineSeparator())
        sb.append("Alter Commands=").append(!myAlterCommands.isEmpty())
        sb.append(System.lineSeparator())
        sb.append("Excel Workbook Path=").append(excelWorkbookPathForDataInsert)
        sb.append(System.lineSeparator())
        sb.append("Excel Insert Table Order= ")
        if (myInsertTableOrder.isEmpty()) {
            sb.append("None provided")
        } else {
            sb.append(System.lineSeparator())
        }
        for (s in myInsertTableOrder) {
            sb.append("\t").append(s).append(System.lineSeparator())
        }
        sb.append(System.lineSeparator())
        sb.append('}')
        return sb.toString()
    }

    init {
        myDatabase = builder.database
        type = Type.NONE
        if (builder.pathToCreationScript != null) {
            // full creation script provided
            type = Type.FULL_SCRIPT
            pathToCreationScript = builder.pathToCreationScript
            myCreationScriptCommands = fillCommandsFromScript(builder.pathToCreationScript)
            if (myCreationScriptCommands.isEmpty()) {
                state = State.NO_TABLES_ERROR
                return
            }
        } else {
            if (builder.pathToTablesScript != null) {
                // use script to create database structure
                type = Type.TABLES
                pathToTablesScript = builder.pathToTablesScript
                myTableCommands = fillCommandsFromScript(builder.pathToTablesScript)
                if (myTableCommands.isEmpty()) {
                    state = State.NO_TABLES_ERROR
                    return
                }
                // now check for insert
                if (builder.pathToInsertScript != null) {
                    // prefer insert via SQL script if it exists
                    type = Type.TABLES_INSERT
                    pathToInsertScript = builder.pathToInsertScript
                    myInsertCommands = fillCommandsFromScript(builder.pathToInsertScript)
                } else {
                    // could be Excel insert
                    if (builder.pathToExcelWorkbook != null) {
                        type = Type.TABLES_EXCEL
                        excelWorkbookPathForDataInsert = builder.pathToExcelWorkbook
                        myInsertTableOrder = ArrayList(builder.tableNamesInInsertOrder)
                    }
                }
                // now check for alter
                if (builder.pathToAlterScript != null) {
                    pathToAlterScript = builder.pathToAlterScript
                    myAlterCommands = fillCommandsFromScript(builder.pathToAlterScript)
                    if (type == Type.TABLES_INSERT) {
                        type = Type.TABLES_INSERT_ALTER
                    } else if (type == Type.TABLES_EXCEL) {
                        type = Type.TABLES_EXCEL_ALTER
                    } else if (type == Type.TABLES) {
                        type = Type.TABLES_ALTER
                    }
                }
            }
        }
        executeCreateTask()
    }

    /**
     * Attempts to execute a configured set of tasks that will create, possibly fill, and
     * alter the database.
     *
     * @return true if the task was executed correctly, false otherwise
     */
    private fun executeCreateTask(): Boolean {
        return when (state) {
            State.UN_EXECUTED ->                 // execute the task
                dbCreateTaskExecution()

            State.EXECUTED -> {
                DatabaseIfc.logger.error("Tried to execute an already executed create task.\n {}", this)
                false
            }

            State.EXECUTION_ERROR -> {
                DatabaseIfc.logger.error("Tried to execute a previously executed task that had errors.\n {}", this)
                false
            }

            State.NO_TABLES_ERROR -> {
                DatabaseIfc.logger.error("Tried to execute a create task with no tables created.\n {}", this)
                false
            }
        }
        return false
    }

    private fun dbCreateTaskExecution(): Boolean {
        var execFlag = false // assume it does not execute
        when (type) {
            Type.NONE -> {
                DatabaseIfc.logger.warn("Attempted to execute a create task with no commands.\n {}", this)
                execFlag = true
                state = State.EXECUTED
            }

            Type.FULL_SCRIPT -> {
                DatabaseIfc.logger.info("Attempting to execute full script create task...\n {}", this)
                execFlag = myDatabase.executeCommands(creationScriptCommands)
            }

            Type.TABLES -> {
                DatabaseIfc.logger.info("Attempting to execute tables only create task. \n{}", this)
                execFlag = myDatabase.executeCommands(tableCommands)
            }

            Type.TABLES_INSERT -> {
                DatabaseIfc.logger.info("Attempting to execute tables plus insert create task.\n{}", this)
                execFlag = myDatabase.executeCommands(tableCommands)
                if (execFlag) {
                    execFlag = myDatabase.executeCommands(insertCommands)
                }
            }

            Type.TABLES_ALTER -> {
                DatabaseIfc.logger.info("Attempting to execute tables plus alter create task.\n{}", this)
                execFlag = myDatabase.executeCommands(tableCommands)
                if (execFlag) {
                    execFlag = myDatabase.executeCommands(alterCommands)
                }
            }

            Type.TABLES_INSERT_ALTER -> {
                DatabaseIfc.logger.info("Attempting to execute create/insert/alter tables create task.\n {}", this)
                execFlag = myDatabase.executeCommands(tableCommands)
                if (execFlag) {
                    execFlag = myDatabase.executeCommands(insertCommands)
                }
                if (execFlag) {
                    execFlag = myDatabase.executeCommands(alterCommands)
                }
            }

            Type.TABLES_EXCEL -> {
                DatabaseIfc.logger.info("Attempting to execute tables create plus Excel import task.\n {}", this)
                execFlag = myDatabase.executeCommands(tableCommands)
                if (execFlag) {
                    try {
                        TODO("write workbook to database")
//                        ExcelUtil.writeWorkbookToDatabase(excelWorkbookPathForDataInsert, true, myDatabase, insertTableOrder)
                    } catch (e: IOException) {
                        execFlag = false
                    }
                }
            }

            Type.TABLES_EXCEL_ALTER -> {
                DatabaseIfc.logger.info("Attempting to execute tables create plus Excel plus alter import task.\n {}", this)
                execFlag = myDatabase.executeCommands(tableCommands)
                if (execFlag) {
                    execFlag = try {
                        TODO("write workbook to database")
 //                       ExcelUtil.writeWorkbookToDatabase(excelWorkbookPathForDataInsert, true, myDatabase, insertTableOrder)
                        myDatabase.executeCommands(alterCommands)
                    } catch (e: IOException) {
                        false
                    }
                }
            }
        }
        if (execFlag) {
            state = State.EXECUTED
            DatabaseIfc.logger.info("The task was successfully executed.")
        } else {
            state = State.EXECUTION_ERROR
            DatabaseIfc.logger.info("The task had execution errors.")
            throw DataAccessException("There was an execution error for task $this see DbLog.log for details")
        }
        return execFlag // note can only get here if execFlag is true because of the execution exception
    }

    /**
     * @param pathToScript the script to parse
     * @return the list of commands from the script
     */
    private fun fillCommandsFromScript(pathToScript: Path?): List<String> {
        requireNotNull(pathToScript) { "The creation script path must not be null" }
        require(!Files.notExists(pathToScript)) { "The creation script file does not exist" }
        val commands: MutableList<String> = ArrayList()
        try {
            commands.addAll(DatabaseIfc.parseQueriesInSQLScript(pathToScript))
        } catch (e: IOException) {
            DatabaseIfc.logger.warn("The script {} t failed to parse.", pathToScript)
        }
        if (commands.isEmpty()) {
            DatabaseIfc.logger.warn("The script {} produced no commands to execute.", pathToScript)
        }
        return commands
    }

    /**
     * A builder that can be used to configure a database creation task through as set of configuration
     * steps.
     */
    class DbCreateTaskBuilder internal constructor(database: DatabaseIfc) : DbCreateTaskExecuteStepIfc,
        WithCreateScriptStepIfc, WithTablesScriptStepIfc, DbCreateTaskFirstStepIfc, AfterTablesOnlyStepIfc,
        DbInsertStepIfc, DBAfterInsertStepIfc, DBAddConstraintsStepIfc {
        internal var pathToCreationScript: Path? = null
        internal var pathToTablesScript: Path? = null
        internal var pathToInsertScript: Path? = null
        internal var pathToExcelWorkbook: Path? = null
        internal var pathToAlterScript: Path? = null
        internal var tableNamesInInsertOrder: List<String>? = null
        internal val database: DatabaseIfc

        init {
            this.database = database
        }

        override fun withCreationScript(pathToScript: Path): DbCreateTaskExecuteStepIfc {
            requireNotNull(pathToScript) { "The provided creation script path was null" }
            require(!Files.notExists(pathToScript)) { "The creation script file does not exist" }
            pathToCreationScript = pathToScript
            return this
        }

        override fun withTables(pathToScript: Path): AfterTablesOnlyStepIfc {
            requireNotNull(pathToScript) { "The provided table script path was null" }
            require(!Files.notExists(pathToScript)) { "The create table script file does not exist" }
            pathToTablesScript = pathToScript
            return this
        }

        override fun withExcelData(
            toExcelWorkbook: Path,
            tableNamesInInsertOrder: List<String>
        ): DBAfterInsertStepIfc {
            requireNotNull(toExcelWorkbook) { "The provided workbook script path was null" }
            require(!Files.notExists(toExcelWorkbook)) { "The Excel workbook file does not exist" }
            requireNotNull(tableNamesInInsertOrder) { "The provided list of table names was null" }
            pathToExcelWorkbook = toExcelWorkbook
            this.tableNamesInInsertOrder = ArrayList(tableNamesInInsertOrder)
            return this
        }

        override fun withInserts(toInsertScript: Path): DBAfterInsertStepIfc {
            requireNotNull(toInsertScript) { "The provided inset script path was null" }
            require(!Files.notExists(toInsertScript)) { "The insert script file does not exist" }
            pathToInsertScript = toInsertScript
            return this
        }

        override fun withConstraints(toAlterScript: Path): DbCreateTaskExecuteStepIfc {
            requireNotNull(toAlterScript) { "The provided alter script path was null" }
            require(!Files.notExists(toAlterScript)) { "The alter table script file does not exist" }
            pathToAlterScript = toAlterScript
            return this
        }

        override fun execute(): DbCreateTask {
            return DbCreateTask(this)
        }
    }

    /**
     * Used to limit the options on the first step
     */
    interface DbCreateTaskFirstStepIfc : WithCreateScriptStepIfc, WithTablesScriptStepIfc

    /**
     * Allows the user to specify a full creation script that puts the database into
     * the state desired by the user.
     */
    interface WithCreateScriptStepIfc {
        /**
         * @param pathToCreationScript a path to a full creation script that specifies the database, must not be null
         * @return A builder step to permit connecting
         */
        fun withCreationScript(pathToCreationScript: Path): DbCreateTaskExecuteStepIfc
    }

    /**
     * Allows the user to specify a script that creates the tables of the database
     */
    interface WithTablesScriptStepIfc {
        /**
         * @param pathToScript a path to a script that specifies the database tables, must not be null
         * @return A builder step to permit connecting
         */
        fun withTables(pathToScript: Path): AfterTablesOnlyStepIfc
    }

    interface AfterTablesOnlyStepIfc : DbCreateTaskExecuteStepIfc, DbInsertStepIfc
    
    interface DbCreateStepIfc : DbCreateTaskExecuteStepIfc {
        /**
         * @param toCreateScript the path to a script that will create the database, must not be null
         * @return a reference to the insert step in the builder process
         */
        fun using(toCreateScript: Path): DbInsertStepIfc
    }

    interface DbInsertStepIfc : DbCreateTaskExecuteStepIfc {

        /**
         * @param toExcelWorkbook a path to an Excel workbook that can be read to insert
         * data into the database, must not be null
         * @param tableNames      a list of table names that need to be filled. Sheets in
         * the workbook must correspond exactly to these names
         * @return a reference to the alter step in the builder process
         */
        fun withExcelData(toExcelWorkbook: Path, tableNames: List<String>): DBAfterInsertStepIfc

        /**
         * @param toInsertScript a path to an SQL script that can be read to insert
         * data into the database, must not be null
         * @return a reference to the alter step in the builder process
         */
        fun withInserts(toInsertScript: Path): DBAfterInsertStepIfc
    }

    interface DBAddConstraintsStepIfc : DbCreateTaskExecuteStepIfc {
        /**
         * @param toConstraintScript a path to an SQL script that can be read to alter the
         * table structure of the database and add constraints, must not be null
         * @return a reference to the alter step in the builder process
         */
        fun withConstraints(toConstraintScript: Path): DbCreateTaskExecuteStepIfc
    }

    interface DBAfterInsertStepIfc : DBAddConstraintsStepIfc, DbCreateTaskExecuteStepIfc
    
    interface DbCreateTaskExecuteStepIfc {
        /**
         * Finishes the builder process of building the creation commands
         *
         * @return an instance of DbCreateCommandList
         */
        fun execute(): DbCreateTask
    }
}