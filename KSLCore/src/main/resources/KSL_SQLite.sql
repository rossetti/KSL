-- noinspection SqlDialectInspectionForFile

-- A database for holding KSL output statistics
-- Created 3-22-2018
-- Author: M. Rossetti, rossetti@uark.edu
--
-- This design assumes that the model hierarchy cannot change during a simulation run
-- The model hierarchy could change between runs. This means that model elements
-- are associated with specific simulation runs (i.e. they are id dependent on simulation runs)
--
-- Revision: April 25, 2019
-- Correct views to ensure display of statistic name being equal to corresponding model element name
-- Assumes API has changed to guarantee stat_name will be the same as element_name
-- within a simulation
--
-- Revision: Nov 3, 2019
-- Changed statistic values written to database to conform to revised Statistic class
-- removing weighted statistics from Statistic class
--
-- Revision: Nov 19, 2022
-- Updated script to be compatible with SQLite
--
-- Revised: Jan 28, 2023
-- Updated script to have controls and random variable parameters
--
-- Revised: Jan 30, 2023
-- Updated script to represent experiments as separate data from runs (executions) of the experiment
--

-- An experiment represents a set of 1 or more simulation runs and the experimental parameters
-- that were used during the runs

CREATE TABLE EXPERIMENT
(
    EXP_ID                     INTEGER PRIMARY KEY,
    SIM_NAME                   VARCHAR(510) NOT NULL,
    MODEL_NAME                 VARCHAR(510) NOT NULL,
    EXP_NAME                   VARCHAR(510) NOT NULL,
    NUM_REPS                   INTEGER      NOT NULL CHECK (NUM_REPS >= 1),
    IS_CHUNKED                 BOOLEAN      NOT NULL,
    LENGTH_OF_REP              DOUBLE PRECISION,
    LENGTH_OF_WARM_UP          DOUBLE PRECISION,
    REP_ALLOWED_EXEC_TIME      BIGINT,
    REP_INIT_OPTION            BOOLEAN      NOT NULL,
    RESET_START_STREAM_OPTION  BOOLEAN      NOT NULL,
    ANTITHETIC_OPTION          BOOLEAN      NOT NULL,
    ADV_NEXT_SUB_STREAM_OPTION BOOLEAN      NOT NULL,
    NUM_STREAM_ADVANCES        INTEGER      NOT NULL,
    GC_AFTER_REP_OPTION        BOOLEAN      NOT NULL
);

-- SIMULATION_RUN captures the execution of replications within a simulation experiment

CREATE TABLE SIMULATION_RUN
(
    RUN_ID               INTEGER      NOT NULL,
    EXP_ID_FK            INTEGER      NOT NULL,
    RUN_NAME             VARCHAR(510) NOT NULL,
    NUM_REPS             INTEGER      NOT NULL CHECK (NUM_REPS >= 1),
    START_REP_ID         INTEGER      NOT NULL CHECK ((1 <= START_REP_ID) AND (START_REP_ID <= LAST_REP_ID)),
    LAST_REP_ID          INTEGER      NOT NULL CHECK (1 <= LAST_REP_ID),
    RUN_START_TIME_STAMP TIMESTAMP,
    RUN_END_TIME_STAMP   TIMESTAMP,
    RUN_ERROR_MSG        VARCHAR(510),
    PRIMARY KEY (RUN_ID, EXP_ID_FK),
    FOREIGN KEY (EXP_ID_FK) REFERENCES EXPERIMENT (EXP_ID) ON DELETE CASCADE
);

-- MODEL_ELEMENT represents the model element hierarchy associated with various
-- simulation runs, i.e. the model elements in the model and their parent/child
-- relationship.  LEFT_COUNT and RIGHT_COUNT uses Joe Celko's SQL for Smarties
-- Advanced SQL Programming Chapter 36 to implement the nested set model for
-- the hierarchy. This allows statistics associated with hierarchical aggregations
-- and subtrees of the model element hierarchy to be more easily queried.

CREATE TABLE MODEL_ELEMENT
(
    EXP_ID_FK    INTEGER      NOT NULL,
    ELEMENT_ID   INTEGER      NOT NULL,
    ELEMENT_NAME VARCHAR(510) NOT NULL,
    CLASS_NAME   VARCHAR(510) NOT NULL,
    PARENT_ID_FK INTEGER,
    PARENT_NAME  VARCHAR(510),
    LEFT_COUNT   INTEGER      NOT NULL CHECK (LEFT_COUNT > 0),
    RIGHT_COUNT  INTEGER      NOT NULL CHECK (RIGHT_COUNT > 1),
    CONSTRAINT TRAVERSAL_ORDER_OKAY CHECK (LEFT_COUNT < RIGHT_COUNT),
    PRIMARY KEY (EXP_ID_FK, ELEMENT_ID),
    UNIQUE (EXP_ID_FK, ELEMENT_NAME),
    FOREIGN KEY (EXP_ID_FK) REFERENCES EXPERIMENT (EXP_ID) ON DELETE CASCADE
);

CREATE INDEX ME_EXP_ID_FK_INDEX ON MODEL_ELEMENT (EXP_ID_FK);

-- CONTROL holds the input controls as designated by the user
-- when annotating with KSLControl

CREATE TABLE CONTROL
(
    CONTROL_ID    INTEGER PRIMARY KEY,
    EXP_ID_FK     INTEGER          NOT NULL,
    ELEMENT_ID_FK INTEGER          NOT NULL,
    KEY_NAME      VARCHAR(510)     NOT NULL,
    CONTROL_VALUE DOUBLE PRECISION NOT NULL,
    LOWER_BOUND   DOUBLE PRECISION,
    UPPER_BOUND   DOUBLE PRECISION,
    PROPERTY_NAME VARCHAR(510)     NOT NULL,
    CONTROL_TYPE  VARCHAR(510)     NOT NULL,
    COMMENT       VARCHAR(510),
    FOREIGN KEY (EXP_ID_FK, ELEMENT_ID_FK)
        REFERENCES MODEL_ELEMENT (EXP_ID_FK, ELEMENT_ID) ON DELETE CASCADE
);

-- RV_PARAMETER holds the random variables from the model that implement
-- the RVParametersIfc interface

CREATE TABLE RV_PARAMETER
(
    RV_PARAM_ID   INTEGER PRIMARY KEY,
    EXP_ID_FK     INTEGER          NOT NULL,
    ELEMENT_ID_FK INTEGER          NOT NULL,
    CLASS_NAME    VARCHAR(510)     NOT NULL,
    DATA_TYPE     VARCHAR(12)      NOT NULL,
    RV_NAME       VARCHAR(510)     NOT NULL,
    PARAM_NAME    VARCHAR(510)     NOT NULL,
    PARAM_VALUE   DOUBLE PRECISION NOT NULL,
    FOREIGN KEY (EXP_ID_FK, ELEMENT_ID_FK)
        REFERENCES MODEL_ELEMENT (EXP_ID_FK, ELEMENT_ID) ON DELETE CASCADE
);

-- WITHIN_REP_STAT represents within replication statistics for each response for each replication of
-- each simulation run of an experiment

CREATE TABLE WITHIN_REP_STAT
(
    ID             INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK  INTEGER NOT NULL,
    SIM_RUN_ID_FK  INTEGER NOT NULL,
    REP_ID         INTEGER NOT NULL CHECK (REP_ID >= 1),
    STAT_NAME      VARCHAR(510),
    STAT_COUNT     DOUBLE PRECISION CHECK (STAT_COUNT >= 0),
    AVERAGE        DOUBLE PRECISION,
    MINIMUM        DOUBLE PRECISION,
    MAXIMUM        DOUBLE PRECISION,
    WEIGHTED_SUM   DOUBLE PRECISION,
    SUM_OF_WEIGHTS DOUBLE PRECISION,
    WEIGHTED_SSQ   DOUBLE PRECISION,
    LAST_VALUE     DOUBLE PRECISION,
    LAST_WEIGHT    DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (RUN_ID) ON DELETE CASCADE,
    UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, REP_ID),
    FOREIGN KEY (ELEMENT_ID_FK) REFERENCES MODEL_ELEMENT (ELEMENT_ID) ON DELETE CASCADE
);

CREATE INDEX WRS_ME_FK_INDEX ON WITHIN_REP_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- WITHIN_REP_COUNTER_STAT represents within replication final value for each counter for each replication of
-- each simulation simulation run of an experiment

CREATE TABLE WITHIN_REP_COUNTER_STAT
(
    ID            INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK INTEGER NOT NULL,
    SIM_RUN_ID_FK INTEGER NOT NULL,
    REP_ID       INTEGER NOT NULL CHECK (REP_ID >= 1),
    STAT_NAME     VARCHAR(510),
    LAST_VALUE    DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (RUN_ID) ON DELETE CASCADE,
    UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, REP_ID),
    FOREIGN KEY (ELEMENT_ID_FK) REFERENCES MODEL_ELEMENT (ELEMENT_ID) ON DELETE CASCADE
);

CREATE INDEX WRCS_ME_FK_INDEX ON WITHIN_REP_COUNTER_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- ACROSS_REP_STAT represents summary statistics for each simulation response across
-- the replications within a simulation run.  Since a simulation run can represent
-- all or part of a set of replications for an experiment, the user must note that
-- the summary statistics are limited to the replications within the run.

CREATE TABLE ACROSS_REP_STAT
(
    ID                    INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK         INTEGER NOT NULL,
    SIM_RUN_ID_FK         INTEGER NOT NULL,
    STAT_NAME             VARCHAR(510),
    STAT_COUNT            DOUBLE PRECISION CHECK (STAT_COUNT >= 0),
    AVERAGE               DOUBLE PRECISION,
    STD_DEV               DOUBLE PRECISION CHECK (STD_DEV >= 0),
    STD_ERR               DOUBLE PRECISION CHECK (STD_ERR >= 0),
    HALF_WIDTH            DOUBLE PRECISION CHECK (HALF_WIDTH >= 0),
    CONF_LEVEL            DOUBLE PRECISION,
    MINIMUM               DOUBLE PRECISION,
    MAXIMUM               DOUBLE PRECISION,
    SUM_OF_OBS            DOUBLE PRECISION,
    DEV_SSQ               DOUBLE PRECISION,
    LAST_VALUE            DOUBLE PRECISION,
    KURTOSIS              DOUBLE PRECISION,
    SKEWNESS              DOUBLE PRECISION,
    LAG1_COV              DOUBLE PRECISION,
    LAG1_CORR             DOUBLE PRECISION,
    VON_NEUMANN_LAG1_STAT DOUBLE PRECISION,
    NUM_MISSING_OBS       DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (RUN_ID) ON DELETE CASCADE,
    FOREIGN KEY (ELEMENT_ID_FK) REFERENCES MODEL_ELEMENT (ELEMENT_ID) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ARS_ME_FK_INDEX ON ACROSS_REP_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- BATCH_STAT represents summary statistics for each simulation response across
-- the batches within a replication of a simulation run. This is produced only if the batch statistics
-- option is used when running the simulation.
-- Note that someone could turn on batching when there is more than on replication. Thus, batching results
-- could be available for each replication of a simulation run.

CREATE TABLE BATCH_STAT
(
    ID                       INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK            INTEGER NOT NULL,
    SIM_RUN_ID_FK            INTEGER NOT NULL,
    REP_ID                   INTEGER NOT NULL CHECK (REP_ID >= 1),
    STAT_NAME                VARCHAR(510),
    STAT_COUNT               DOUBLE PRECISION CHECK (STAT_COUNT >= 0),
    AVERAGE                  DOUBLE PRECISION,
    STD_DEV                  DOUBLE PRECISION CHECK (STD_DEV >= 0),
    STD_ERR                  DOUBLE PRECISION CHECK (STD_ERR >= 0),
    HALF_WIDTH               DOUBLE PRECISION CHECK (HALF_WIDTH >= 0),
    CONF_LEVEL               DOUBLE PRECISION,
    MINIMUM                  DOUBLE PRECISION,
    MAXIMUM                  DOUBLE PRECISION,
    SUM_OF_OBS               DOUBLE PRECISION,
    DEV_SSQ                  DOUBLE PRECISION,
    LAST_VALUE               DOUBLE PRECISION,
    KURTOSIS                 DOUBLE PRECISION,
    SKEWNESS                 DOUBLE PRECISION,
    LAG1_COV                 DOUBLE PRECISION,
    LAG1_CORR                DOUBLE PRECISION,
    VON_NEUMANN_LAG1_STAT     DOUBLE PRECISION,
    NUM_MISSING_OBS          DOUBLE PRECISION,
    MIN_BATCH_SIZE           DOUBLE PRECISION,
    MIN_NUM_BATCHES          DOUBLE PRECISION,
    MAX_NUM_BATCHES_MULTIPLE DOUBLE PRECISION,
    MAX_NUM_BATCHES          DOUBLE PRECISION,
    NUM_REBATCHES            DOUBLE PRECISION,
    CURRENT_BATCH_SIZE       DOUBLE PRECISION,
    AMT_UNBATCHED            DOUBLE PRECISION,
    TOTAL_NUM_OBS            DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (RUN_ID) ON DELETE CASCADE,
    FOREIGN KEY (ELEMENT_ID_FK) REFERENCES MODEL_ELEMENT (ELEMENT_ID) ON DELETE CASCADE
);

CREATE INDEX BS_ME_FK_INDEX ON BATCH_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- WITHIN_REP_RESPONSE_VIEW represents a reduced view of within replication statistics containing only the average for the replication

CREATE VIEW WITHIN_REP_RESPONSE_VIEW
            (EXP_ID, EXP_NAME, NUM_REPS, SIM_RUN_ID_FK, RUN_NAME, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID, AVERAGE)
AS
SELECT EXP_ID,
       EXP_NAME,
       EXPERIMENT.NUM_REPS,
       SIM_RUN_ID_FK,
       RUN_NAME,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       AVERAGE
FROM EXPERIMENT,
     SIMULATION_RUN,
     MODEL_ELEMENT,
     WITHIN_REP_STAT
WHERE EXPERIMENT.EXP_ID = SIMULATION_RUN.EXP_ID_FK
  AND SIMULATION_RUN.RUN_ID = WITHIN_REP_STAT.SIM_RUN_ID_FK
  AND EXPERIMENT.EXP_ID = MODEL_ELEMENT.EXP_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = WITHIN_REP_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = WITHIN_REP_STAT.STAT_NAME
ORDER BY EXP_ID, EXP_NAME, EXPERIMENT.NUM_REPS, SIM_RUN_ID_FK, RUN_NAME, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID;

-- WITHIN_REP_COUNTER_VIEW represents a reduced view of within replication counters containing only the last value for the replication
-- not sure if this has to go through MODEL_ELEMENT
CREATE VIEW WITHIN_REP_COUNTER_VIEW
            (EXP_ID, EXP_NAME, NUM_REPS, SIM_RUN_ID_FK, RUN_NAME, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID, LAST_VALUE)
AS
SELECT EXP_ID,
       EXP_NAME,
       EXPERIMENT.NUM_REPS,
       SIM_RUN_ID_FK,
       RUN_NAME,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       LAST_VALUE
FROM EXPERIMENT,
     SIMULATION_RUN,
     WITHIN_REP_COUNTER_STAT,
     MODEL_ELEMENT
WHERE EXPERIMENT.EXP_ID = SIMULATION_RUN.EXP_ID_FK
  AND SIMULATION_RUN.RUN_ID = WITHIN_REP_COUNTER_STAT.SIM_RUN_ID_FK
  AND EXPERIMENT.EXP_ID = MODEL_ELEMENT.EXP_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = WITHIN_REP_COUNTER_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = WITHIN_REP_COUNTER_STAT.STAT_NAME
ORDER BY EXP_ID, EXP_NAME, EXPERIMENT.NUM_REPS, SIM_RUN_ID_FK, RUN_NAME, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID;

-- WITHIN_REP_VIEW combines the WITHIN_REP_COUNTER_VIEW and WITHIN_REP_RESPONSE_VIEW into one table from which across
-- replication or other statistical summaries by replication can be produced

CREATE VIEW WITHIN_REP_VIEW
            (EXP_ID, EXP_NAME, NUM_REPS, SIM_RUN_ID_FK, RUN_NAME, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID, REP_VALUE) AS
SELECT EXP_ID,
       EXP_NAME,
       NUM_REPS,
       SIM_RUN_ID_FK,
       RUN_NAME,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       AVERAGE AS REP_VALUE
FROM WITHIN_REP_RESPONSE_VIEW
UNION
SELECT EXP_ID,
       EXP_NAME,
       NUM_REPS,
       SIM_RUN_ID_FK,
       RUN_NAME,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       LAST_VALUE AS REP_VALUE
FROM WITHIN_REP_COUNTER_VIEW;

-- EXP_STAT_REP_VIEW is a reduced view of WITHIN_REP_VIEW that removes the columns about the associated simulation run(s).

CREATE VIEW EXP_STAT_REP_VIEW(EXP_ID, EXP_NAME, STAT_NAME, REP_ID, REP_VALUE) AS
    SELECT EXP_ID, EXP_NAME, STAT_NAME, REP_ID, REP_VALUE
FROM WITHIN_REP_VIEW
ORDER BY EXP_ID, EXP_NAME, STAT_NAME, REP_ID;

-- ACROSS_REP_VIEW represents a reduced view of the across replication responses containing only n, avg, and stddev
-- for each experiment that is not chunked.  That is, for those experiments that have only 1 simulation run
-- that represents all the replications

CREATE VIEW ACROSS_REP_VIEW (EXP_ID, EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV)
AS
SELECT EXP_ID, EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV
FROM EXPERIMENT,
     SIMULATION_RUN,
     ACROSS_REP_STAT,
     MODEL_ELEMENT
WHERE EXPERIMENT.IS_CHUNKED = FALSE
  AND EXPERIMENT.NUM_REPS = SIMULATION_RUN.NUM_REPS
  AND EXPERIMENT.EXP_ID = SIMULATION_RUN.EXP_ID_FK
  AND SIMULATION_RUN.RUN_ID = ACROSS_REP_STAT.SIM_RUN_ID_FK
  AND EXPERIMENT.EXP_ID = MODEL_ELEMENT.EXP_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = ACROSS_REP_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = ACROSS_REP_STAT.STAT_NAME
ORDER BY EXP_ID, EXP_NAME, STAT_NAME;

-- BATCH_STAT_VIEW represents a reduced view of the batch statistics responses containing only n, avg, and stddev
-- Note that someone could turn on batching when there is more than on replication. Thus, batching results
-- could be available for each replication of a simulation run.

CREATE VIEW BATCH_STAT_VIEW (EXP_ID, EXP_NAME, SIM_RUN_ID_FK, REP_ID, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV)
AS
SELECT EXP_ID,
       EXP_NAME,
       SIM_RUN_ID_FK,
       REP_ID,
       STAT_NAME,
       STAT_COUNT,
       AVERAGE,
       STD_DEV
FROM EXPERIMENT,
     SIMULATION_RUN,
     BATCH_STAT,
     MODEL_ELEMENT
WHERE EXPERIMENT.EXP_ID = SIMULATION_RUN.EXP_ID_FK
  AND SIMULATION_RUN.RUN_ID = BATCH_STAT.SIM_RUN_ID_FK
  AND EXPERIMENT.EXP_ID = MODEL_ELEMENT.EXP_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = BATCH_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = BATCH_STAT.STAT_NAME
ORDER BY EXP_ID, EXP_NAME, SIM_RUN_ID_FK, REP_ID, STAT_NAME;

-- PW_DIFF_WITHIN_REP_VIEW computes the pairwise differences across difference simulation experiments
-- always takes the difference A - B, where A is a simulation experiment with a higher ID number than B
-- for each experiment.

create view PW_DIFF_WITHIN_REP_VIEW
as
select EXPERIMENT.SIM_NAME,
       A.STAT_NAME,
       A.REP_ID,
       A.EXP_ID                                        AS A_EXP_ID,
       A.EXP_NAME                                      as A_EXP_NAME,
       A.REP_VALUE                                     as A_VALUE,
       B.EXP_ID                                        as B_EXP_ID,
       B.EXP_NAME                                      as B_EXP_NAME,
       B.REP_VALUE                                     as B_VALUE,
       '(' || A.EXP_NAME || ' - ' || B.EXP_NAME || ')' as DIFF_NAME,
       (A.REP_VALUE - B.REP_VALUE)                     as A_MINUS_B
from WITHIN_REP_VIEW as A,
     WITHIN_REP_VIEW as B,
     EXPERIMENT
where EXPERIMENT.EXP_ID = A.EXP_ID
  and A.NUM_REPS = B.NUM_REPS
  and A.STAT_NAME = B.STAT_NAME
  and A.REP_ID = B.REP_ID
  and A.EXP_ID > B.EXP_ID;

