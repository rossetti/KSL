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

-- Revised: Feb 5, 2023
-- Updated script to represent experiments as separate data from runs (executions) of the experiment

-- Revised: June 13, 2024
-- Updated script to store IntegerFrequencyResponse and HistogramResponse data

-- An experiment represents a set of 1 or more simulation runs and the experimental parameters
-- that were used during the runs

-- Revised: June 28, 2024
-- Changed to support DuckDb which has no cascade delete option on foreign keys
-- and no GENERATED key word
-- CANNOT proceed because DuckDb does not support cascade delete

CREATE SCHEMA KSL_DB;

CREATE SEQUENCE KSL_DB.exp_id;

CREATE TABLE KSL_DB.EXPERIMENT
(
    EXP_ID                     INTEGER DEFAULT nextval('KSL_DB.exp_id') PRIMARY KEY,
    SIM_NAME                   VARCHAR(510) NOT NULL,
    MODEL_NAME                 VARCHAR(510) NOT NULL,
    EXP_NAME                   VARCHAR(510) NOT NULL,
    NUM_CHUNKS                 INTEGER      NOT NULL,
    LENGTH_OF_REP              DOUBLE PRECISION,
    LENGTH_OF_WARM_UP          DOUBLE PRECISION,
    REP_ALLOWED_EXEC_TIME      BIGINT,
    REP_INIT_OPTION            BOOLEAN      NOT NULL,
    RESET_START_STREAM_OPTION  BOOLEAN      NOT NULL,
    ANTITHETIC_OPTION          BOOLEAN      NOT NULL,
    ADV_NEXT_SUB_STREAM_OPTION BOOLEAN      NOT NULL,
    NUM_STREAM_ADVANCES        INTEGER      NOT NULL,
    GC_AFTER_REP_OPTION        BOOLEAN      NOT NULL,
    UNIQUE (EXP_NAME)
);

-- SIMULATION_RUN captures the execution of a simulation experiment and its related options

CREATE SEQUENCE KSL_DB.run_id;

CREATE TABLE KSL_DB.SIMULATION_RUN
(
    RUN_ID               INTEGER DEFAULT nextval('KSL_DB.run_id') PRIMARY KEY,
    EXP_ID_FK            INTEGER      NOT NULL,
    RUN_NAME             VARCHAR(510) NOT NULL,
    NUM_REPS             INTEGER      NOT NULL CHECK (NUM_REPS >= 1),
    START_REP_ID         INTEGER      NOT NULL CHECK (1 <= START_REP_ID),
    LAST_REP_ID          INTEGER CHECK (1 <= LAST_REP_ID),
    RUN_START_TIME_STAMP TIMESTAMP,
    RUN_END_TIME_STAMP   TIMESTAMP,
    RUN_ERROR_MSG        VARCHAR(510),
    UNIQUE (EXP_ID_FK, RUN_NAME),
    FOREIGN KEY (EXP_ID_FK) REFERENCES KSL_DB.EXPERIMENT (EXP_ID)
);

-- MODEL_ELEMENT represents the model element hierarchy associated with various
-- simulation runs, i.e. the model elements in the model and their parent/child
-- relationship.  LEFT_COUNT and RIGHT_COUNT uses Joe Celko's SQL for Smarties
-- Advanced SQL Programming Chapter 36 to implement the nested set model for
-- the hierarchy. This allows statistics associated with hierarchical aggregations
-- and subtrees of the model element hierarchy to be more easily queried.
CREATE TABLE KSL_DB.MODEL_ELEMENT
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
    CONSTRAINT ME_PRIM_KY PRIMARY KEY (EXP_ID_FK, ELEMENT_ID),
    CONSTRAINT ME_NAME_UNIQUE UNIQUE (EXP_ID_FK, ELEMENT_NAME),
    CONSTRAINT ME_EXP_ID_FK FOREIGN KEY (EXP_ID_FK) REFERENCES KSL_DB.EXPERIMENT (EXP_ID)
);

CREATE INDEX ME_EXP_ID_FK_INDEX ON KSL_DB.MODEL_ELEMENT (EXP_ID_FK);

-- CONTROL holds the input controls as designated by the user
-- when annotating with KSLControl

CREATE SEQUENCE KSL_DB.control_id;

CREATE TABLE KSL_DB.CONTROL
(
    CONTROL_ID    INTEGER          NOT NULL DEFAULT nextval('KSL_DB.control_id') PRIMARY KEY,
    EXP_ID_FK     INTEGER          NOT NULL,
    ELEMENT_ID_FK INTEGER          NOT NULL,
    KEY_NAME      VARCHAR(510)     NOT NULL,
    CONTROL_VALUE DOUBLE PRECISION,
    LOWER_BOUND   DOUBLE PRECISION,
    UPPER_BOUND   DOUBLE PRECISION,
    PROPERTY_NAME VARCHAR(510)     NOT NULL,
    CONTROL_TYPE  VARCHAR(510)     NOT NULL,
    COMMENT       VARCHAR(510),
    CONSTRAINT CONTROL_EXP_ID_FK FOREIGN KEY (EXP_ID_FK) REFERENCES KSL_DB.EXPERIMENT (EXP_ID),
    CONSTRAINT CTL_MODEL_ELEMENT_FK FOREIGN KEY (EXP_ID_FK, ELEMENT_ID_FK)
        REFERENCES KSL_DB.MODEL_ELEMENT (EXP_ID_FK, ELEMENT_ID)
);

-- RV_PARAMETER holds the random variables from the model that implement
-- the RVParametersIfc interface

CREATE SEQUENCE KSL_DB.rv_id;

CREATE TABLE KSL_DB.RV_PARAMETER
(
    RV_PARAM_ID   INTEGER          NOT NULL DEFAULT nextval('KSL_DB.rv_id') PRIMARY KEY,
    EXP_ID_FK     INTEGER          NOT NULL,
    ELEMENT_ID_FK INTEGER          NOT NULL,
    CLASS_NAME    VARCHAR(510)     NOT NULL,
    DATA_TYPE     VARCHAR(12)      NOT NULL,
    RV_NAME       VARCHAR(510)     NOT NULL,
    PARAM_NAME    VARCHAR(510)     NOT NULL,
    PARAM_VALUE   DOUBLE PRECISION NOT NULL,
    CONSTRAINT RV_PARAM_EXP_ID_FK FOREIGN KEY (EXP_ID_FK) REFERENCES KSL_DB.EXPERIMENT (EXP_ID),
    CONSTRAINT RV_PARAM_MODEL_ELEMENT_FK FOREIGN KEY (EXP_ID_FK, ELEMENT_ID_FK)
        REFERENCES KSL_DB.MODEL_ELEMENT (EXP_ID_FK, ELEMENT_ID)
);

-- WITHIN_REP_STAT represents within replication statistics for each replication of
-- each simulation for each response
CREATE SEQUENCE KSL_DB.within_rep_id;

CREATE TABLE KSL_DB.WITHIN_REP_STAT
(
    ID             INTEGER NOT NULL DEFAULT nextval('KSL_DB.within_rep_id') PRIMARY KEY,
    ELEMENT_ID_FK  INTEGER NOT NULL,
    SIM_RUN_ID_FK  INTEGER NOT NULL,
    REP_ID       INTEGER NOT NULL CHECK (REP_ID >= 1),
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
    CONSTRAINT WRS_SIMRUN_FK FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES KSL_DB.SIMULATION_RUN (RUN_ID),
    CONSTRAINT WRS_UNIQUE_ELEMENT_SIMRUN_REPNUM UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, REP_ID)
);

CREATE INDEX WRS_ME_FK_INDEX ON KSL_DB.WITHIN_REP_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- ACROSS_REP_STAT represents summary statistics for each simulation response across
-- the replications within the experiment.

CREATE SEQUENCE KSL_DB.across_rep_id;

CREATE TABLE KSL_DB.ACROSS_REP_STAT
(
    ID                   INTEGER NOT NULL DEFAULT nextval('KSL_DB.across_rep_id') PRIMARY KEY,
    ELEMENT_ID_FK        INTEGER NOT NULL,
    SIM_RUN_ID_FK        INTEGER NOT NULL,
    STAT_NAME            VARCHAR(510),
    STAT_COUNT           DOUBLE PRECISION CHECK (STAT_COUNT >= 0),
    AVERAGE              DOUBLE PRECISION,
    STD_DEV              DOUBLE PRECISION CHECK (STD_DEV >= 0),
    STD_ERR              DOUBLE PRECISION CHECK (STD_ERR >= 0),
    HALF_WIDTH           DOUBLE PRECISION CHECK (HALF_WIDTH >= 0),
    CONF_LEVEL           DOUBLE PRECISION,
    MINIMUM              DOUBLE PRECISION,
    MAXIMUM              DOUBLE PRECISION,
    SUM_OF_OBS           DOUBLE PRECISION,
    DEV_SSQ              DOUBLE PRECISION,
    LAST_VALUE           DOUBLE PRECISION,
    KURTOSIS             DOUBLE PRECISION,
    SKEWNESS             DOUBLE PRECISION,
    LAG1_COV             DOUBLE PRECISION,
    LAG1_CORR            DOUBLE PRECISION,
    VON_NEUMANN_LAG1_STAT DOUBLE PRECISION,
    NUM_MISSING_OBS      DOUBLE PRECISION,
    CONSTRAINT ARS_SIMRUN_FK FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES KSL_DB.SIMULATION_RUN (RUN_ID)
);

CREATE UNIQUE INDEX ARS_ME_FK_INDEX ON KSL_DB.ACROSS_REP_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- WITHIN_REP_COUNTER_STAT represents within replication final value for each replication of
-- each simulation for each counter

CREATE SEQUENCE KSL_DB.counter_id;

CREATE TABLE KSL_DB.WITHIN_REP_COUNTER_STAT
(
    ID            INTEGER NOT NULL DEFAULT nextval('KSL_DB.counter_id') PRIMARY KEY,
    ELEMENT_ID_FK INTEGER NOT NULL,
    SIM_RUN_ID_FK INTEGER NOT NULL,
    REP_ID      INTEGER NOT NULL CHECK (REP_ID >= 1),
    STAT_NAME     VARCHAR(510),
    LAST_VALUE    DOUBLE PRECISION,
    CONSTRAINT WRCS_SIMRUN_FK FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES KSL_DB.SIMULATION_RUN (RUN_ID),
    CONSTRAINT WRCS_UNIQUE_ELEMENT_SIMRUN_REPNUM UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, REP_ID)
);

CREATE INDEX WRCS_ME_FK_INDEX ON KSL_DB.WITHIN_REP_COUNTER_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- BATCH_STAT represents summary statistics for each simulation response across
-- the batches within a replication. This is produced only if the batch statistics
-- option is used when running the simulation.
CREATE SEQUENCE KSL_DB.batch_id;

CREATE TABLE KSL_DB.BATCH_STAT
(
    ID                       INTEGER NOT NULL DEFAULT nextval('KSL_DB.batch_id') PRIMARY KEY,
    ELEMENT_ID_FK            INTEGER NOT NULL,
    SIM_RUN_ID_FK            INTEGER NOT NULL,
    REP_ID                 INTEGER NOT NULL CHECK (REP_ID >= 1),
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
    CONSTRAINT BS_SIMRUN_FK FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES KSL_DB.SIMULATION_RUN (RUN_ID)
);

CREATE INDEX BS_ME_FK_INDEX ON KSL_DB.BATCH_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

CREATE SEQUENCE KSL_DB.hist_id;

CREATE TABLE KSL_DB.HISTOGRAM
(
    ID                 INTEGER          NOT NULL DEFAULT nextval('KSL_DB.hist_id') PRIMARY KEY,
    ELEMENT_ID_FK      INTEGER          NOT NULL,
    SIM_RUN_ID_FK      INTEGER          NOT NULL,
    RESPONSE_ID_FK     INTEGER          NOT NULL,
    RESPONSE_NAME      VARCHAR(510)     NOT NULL,
    BIN_LABEL          VARCHAR(510)     NOT NULL,
    BIN_NUM            INTEGER          NOT NULL,
    BIN_LOWER_LIMIT    DOUBLE PRECISION,
    BIN_UPPER_LIMIT    DOUBLE PRECISION,
    BIN_COUNT          DOUBLE PRECISION,
    BIN_CUM_COUNT      DOUBLE PRECISION,
    BIN_PROPORTION     DOUBLE PRECISION,
    BIN_CUM_PROPORTION DOUBLE PRECISION,
    CONSTRAINT HIST_SIMRUN_FK FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES KSL_DB.SIMULATION_RUN (RUN_ID),
    CONSTRAINT HIST_UNIQUE_ELEMENT_SIMRUN_BINNUM UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, BIN_NUM)
);

CREATE SEQUENCE KSL_DB.freq_id;

CREATE TABLE KSL_DB.FREQUENCY
(
    ID             INTEGER      NOT NULL DEFAULT nextval('KSL_DB.freq_id') PRIMARY KEY,
    ELEMENT_ID_FK  INTEGER      NOT NULL,
    SIM_RUN_ID_FK  INTEGER      NOT NULL,
    NAME           VARCHAR(510) NOT NULL,
    CELL_LABEL     VARCHAR(510) NOT NULL,
    VALUE          INTEGER      NOT NULL,
    COUNT          DOUBLE PRECISION,
    CUM_COUNT      DOUBLE PRECISION,
    PROPORTION     DOUBLE PRECISION,
    CUM_PROPORTION DOUBLE PRECISION,
    CONSTRAINT FREQ_SIMRUN_FK FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES KSL_DB.SIMULATION_RUN (RUN_ID),
    CONSTRAINT FREQ_UNIQUE_ELEMENT_SIMRUN_VALUE UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, VALUE)
);

-- WITHIN_REP_RESPONSE_VIEW represents a reduced view of within replication statistics containing only the average for the replication
CREATE VIEW KSL_DB.WITHIN_REP_RESPONSE_VIEW
            (EXP_NAME, RUN_NAME, NUM_REPS, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID, AVERAGE)
AS
SELECT EXP_NAME,
       RUN_NAME,
       KSL_DB.SIMULATION_RUN.NUM_REPS,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       AVERAGE
FROM KSL_DB.EXPERIMENT,
     KSL_DB.SIMULATION_RUN,
     KSL_DB.MODEL_ELEMENT,
     KSL_DB.WITHIN_REP_STAT
WHERE KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.SIMULATION_RUN.EXP_ID_FK
  AND KSL_DB.SIMULATION_RUN.RUN_ID = KSL_DB.WITHIN_REP_STAT.SIM_RUN_ID_FK
  AND KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.MODEL_ELEMENT.EXP_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_ID = KSL_DB.WITHIN_REP_STAT.ELEMENT_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_NAME = KSL_DB.WITHIN_REP_STAT.STAT_NAME
ORDER BY EXP_NAME, STAT_NAME, REP_ID;

-- WITHIN_REP_COUNTER_VIEW represents a reduced view of within replication counters containing only the last value for the replication
CREATE VIEW KSL_DB.WITHIN_REP_COUNTER_VIEW
            (EXP_NAME, RUN_NAME, NUM_REPS, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID, LAST_VALUE)
AS
SELECT EXP_NAME,
       RUN_NAME,
       KSL_DB.SIMULATION_RUN.NUM_REPS,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       LAST_VALUE
FROM KSL_DB.EXPERIMENT,
     KSL_DB.SIMULATION_RUN,
     KSL_DB.WITHIN_REP_COUNTER_STAT,
     KSL_DB.MODEL_ELEMENT
WHERE KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.SIMULATION_RUN.EXP_ID_FK
  AND KSL_DB.SIMULATION_RUN.RUN_ID = KSL_DB.WITHIN_REP_COUNTER_STAT.SIM_RUN_ID_FK
  AND KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.MODEL_ELEMENT.EXP_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_ID = KSL_DB.WITHIN_REP_COUNTER_STAT.ELEMENT_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_NAME = KSL_DB.WITHIN_REP_COUNTER_STAT.STAT_NAME
ORDER BY EXP_NAME, STAT_NAME, REP_ID;

-- WITHIN_REP_VIEW combines the WITHIN_REP_COUNTER_VIEW and WITHIN_REP_RESPONSE_VIEW into one table from which across
-- replication or other statistical summaries by replication can be produced

CREATE VIEW KSL_DB.WITHIN_REP_VIEW
            (EXP_NAME, RUN_NAME, NUM_REPS, START_REP_ID, LAST_REP_ID, STAT_NAME, REP_ID, REP_VALUE) AS
SELECT EXP_NAME,
       RUN_NAME,
       NUM_REPS,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       AVERAGE AS REP_VALUE
FROM KSL_DB.WITHIN_REP_RESPONSE_VIEW
UNION
SELECT EXP_NAME,
       RUN_NAME,
       NUM_REPS,
       START_REP_ID,
       LAST_REP_ID,
       STAT_NAME,
       REP_ID,
       LAST_VALUE AS REP_VALUE
FROM KSL_DB.WITHIN_REP_COUNTER_VIEW
ORDER BY EXP_NAME, STAT_NAME, REP_ID;

-- EXP_STAT_REP_VIEW is a reduced view of WITHIN_REP_VIEW that removes the columns about the associated simulation run(s).

CREATE VIEW KSL_DB.EXP_STAT_REP_VIEW(EXP_NAME, STAT_NAME, REP_ID, REP_VALUE) AS
SELECT EXP_NAME, STAT_NAME, REP_ID, REP_VALUE
FROM KSL_DB.WITHIN_REP_VIEW
ORDER BY EXP_NAME, STAT_NAME, REP_ID;

-- ACROSS_REP_VIEW represents a reduced view of the across replication responses containing only n, avg, and stddev
-- for each experiment that is not chunked.  That is, for those experiments that have only 1 simulation run
-- that represents all the replications

CREATE VIEW KSL_DB.ACROSS_REP_VIEW (EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV)
AS
SELECT EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV
FROM KSL_DB.EXPERIMENT,
     KSL_DB.SIMULATION_RUN,
     KSL_DB.ACROSS_REP_STAT,
     KSL_DB.MODEL_ELEMENT
WHERE KSL_DB.EXPERIMENT.NUM_CHUNKS = 1
  AND KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.SIMULATION_RUN.EXP_ID_FK
  AND KSL_DB.SIMULATION_RUN.RUN_ID = KSL_DB.ACROSS_REP_STAT.SIM_RUN_ID_FK
  AND KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.MODEL_ELEMENT.EXP_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_ID = KSL_DB.ACROSS_REP_STAT.ELEMENT_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_NAME = KSL_DB.ACROSS_REP_STAT.STAT_NAME
ORDER BY EXP_NAME, STAT_NAME;

-- BATCH_STAT_VIEW represents a reduced view of the batch statistics responses containing only n, avg, and stddev
-- Note that someone could turn on batching when there is more than on replication. Thus, batching results
-- could be available for each replication of a simulation run.

CREATE VIEW KSL_DB.BATCH_STAT_VIEW (EXP_NAME, RUN_NAME, REP_ID, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV)
AS
SELECT EXP_NAME,
       RUN_NAME,
       REP_ID,
       STAT_NAME,
       STAT_COUNT,
       AVERAGE,
       STD_DEV
FROM KSL_DB.EXPERIMENT,
     KSL_DB.SIMULATION_RUN,
     KSL_DB.BATCH_STAT,
     KSL_DB.MODEL_ELEMENT
WHERE KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.SIMULATION_RUN.EXP_ID_FK
  AND KSL_DB.SIMULATION_RUN.RUN_ID = KSL_DB.BATCH_STAT.SIM_RUN_ID_FK
  AND KSL_DB.EXPERIMENT.EXP_ID = KSL_DB.MODEL_ELEMENT.EXP_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_ID = KSL_DB.BATCH_STAT.ELEMENT_ID_FK
  AND KSL_DB.MODEL_ELEMENT.ELEMENT_NAME = KSL_DB.BATCH_STAT.STAT_NAME
ORDER BY EXP_NAME, RUN_NAME, REP_ID, STAT_NAME;

-- PW_DIFF_WITHIN_REP_VIEW computes the pairwise differences across difference simulation experiments
-- always takes the difference A - B, where A is a simulation experiment with a higher ID number than B
-- for each experiment.

create view KSL_DB.PW_DIFF_WITHIN_REP_VIEW
as
select EA.SIM_NAME,
       A.STAT_NAME,
       A.REP_ID,
       A.EXP_NAME                                      as A_EXP_NAME,
       A.REP_VALUE                                     as A_VALUE,
       B.EXP_NAME                                      as B_EXP_NAME,
       B.REP_VALUE                                     as B_VALUE,
       '(' || A.EXP_NAME || ' - ' || B.EXP_NAME || ')' as DIFF_NAME,
       (A.REP_VALUE - B.REP_VALUE)                     as A_MINUS_B
from KSL_DB.WITHIN_REP_VIEW as A,
     KSL_DB.WITHIN_REP_VIEW as B,
     KSL_DB.EXPERIMENT as EA,
     KSL_DB.EXPERIMENT as EB
where EA.EXP_NAME = A.EXP_NAME
  and EB.EXP_NAME = B.EXP_NAME
  and A.NUM_REPS = B.NUM_REPS
  and A.STAT_NAME = B.STAT_NAME
  and A.REP_ID = B.REP_ID
  and EA.EXP_ID > EB.EXP_ID;

--
-- PW_DIFF_AR_REP_VIEW computes the across replication summary statistics over the pairwise differences
-- select statement works, but create view does not work for derby, 3-28-2019
-- works for postgres and hsqldb
--
-- create view KSL_DB.PW_DIFF_AR_REP_VIEW (SIM_NAME, STAT_NAME, A_EXP_NAME, B_EXP_NAME, DIFF_NAME, AVG_A, STD_DEV_A,
--                                         AVG_B, STD_DEV_B, AVG_DIFF_A_MINUS_B, STD_DEV_DIFF_A_MINUS_B, STAT_COUNT)
-- as (select SIM_NAME, STAT_NAME, A_EXP_NAME, B_EXP_NAME, DIFF_NAME, AVG(A_VALUE) as AVG_A, STDDEV_SAMP(A_VALUE) as STD_DEV_A,
--            AVG(B_VALUE) as AVG_B, STDDEV_SAMP(B_VALUE) as STD_DEV_B,
--            AVG(A_MINUS_B) as AVG_DIFF_A_MINUS_B, STDDEV_SAMP(A_MINUS_B) as STD_DEV_DIFF_A_MINUS_B,
--            COUNT(A_MINUS_B) as STAT_COUNT
--     from KSL_DB.PW_DIFF_WITHIN_REP_VIEW
--     group by SIM_NAME, STAT_NAME, A_EXP_NAME, B_EXP_NAME, DIFF_NAME);




