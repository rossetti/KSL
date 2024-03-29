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

-- SIMULATION_RUN captures the execution of a simulation experiment and its related options

CREATE TABLE SIMULATION_RUN
(
    ID                         INTEGER PRIMARY KEY,
    SIM_NAME                   VARCHAR(510) NOT NULL,
    MODEL_NAME                 VARCHAR(510) NOT NULL,
    EXP_NAME                   VARCHAR(510) NOT NULL,
    EXP_START_TIME_STAMP       TIMESTAMP,
    EXP_END_TIME_STAMP         TIMESTAMP,
    NUM_REPS                   INTEGER      NOT NULL CHECK (NUM_REPS >= 1),
    LAST_REP                   INTEGER,
    LENGTH_OF_REP              DOUBLE PRECISION,
    LENGTH_OF_WARM_UP          DOUBLE PRECISION,
    HAS_MORE_REPS              BOOLEAN,
    REP_ALLOWED_EXEC_TIME      BIGINT,
    REP_INIT_OPTION            BOOLEAN,
    RESET_START_STREAM_OPTION  BOOLEAN,
    ANTITHETIC_OPTION          BOOLEAN,
    ADV_NEXT_SUB_STREAM_OPTION BOOLEAN,
    NUM_STREAM_ADVANCES        INTEGER,
    UNIQUE (SIM_NAME, EXP_NAME)
);

-- MODEL_ELEMENT represents the model element hierarchy associated with various 
-- simulation runs, i.e. the model elements in the model and their parent/child
-- relationship.  LEFT_COUNT and RIGHT_COUNT uses Joe Celko's SQL for Smarties
-- Advanced SQL Programming Chapter 36 to implement the nested set model for
-- the hierarchy. This allows statistics associated with hierarchical aggregations
-- and subtrees of the model element hierarchy to be more easily queried.

CREATE TABLE MODEL_ELEMENT
(
    SIM_RUN_ID_FK INTEGER      NOT NULL,
    ELEMENT_ID    INTEGER      NOT NULL,
    ELEMENT_NAME  VARCHAR(510) NOT NULL,
    CLASS_NAME    VARCHAR(510) NOT NULL,
    PARENT_ID_FK  INTEGER,
    PARENT_NAME   VARCHAR(510),
    LEFT_COUNT    INTEGER      NOT NULL CHECK (LEFT_COUNT > 0),
    RIGHT_COUNT   INTEGER      NOT NULL CHECK (RIGHT_COUNT > 1),
    CONSTRAINT TRAVERSAL_ORDER_OKAY CHECK (LEFT_COUNT < RIGHT_COUNT),
    PRIMARY KEY (SIM_RUN_ID_FK, ELEMENT_ID),
    UNIQUE (SIM_RUN_ID_FK, ELEMENT_NAME),
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (ID) ON DELETE CASCADE
);

CREATE INDEX ME_SIMRUN_FK_INDEX ON MODEL_ELEMENT(SIM_RUN_ID_FK);

-- WITHIN_REP_STAT represents within replication statistics for each replication of
-- each simulation for each response

CREATE TABLE WITHIN_REP_STAT
(
    ID             INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK  INTEGER NOT NULL,
    SIM_RUN_ID_FK  INTEGER NOT NULL,
    REP_NUM        INTEGER NOT NULL CHECK (REP_NUM >= 1),
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
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (ID) ON DELETE CASCADE,
    UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, REP_NUM),
    FOREIGN KEY (SIM_RUN_ID_FK, ELEMENT_ID_FK)
        REFERENCES MODEL_ELEMENT (SIM_RUN_ID_FK, ELEMENT_ID) ON DELETE CASCADE
);

CREATE INDEX WRS_ME_FK_INDEX ON WITHIN_REP_STAT(SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- ACROSS_REP_STAT represents summary statistics for each simulation response across
-- the replications within the experiment.

CREATE TABLE ACROSS_REP_STAT
(
    ID                   INTEGER NOT NULL PRIMARY KEY,
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
    VON_NEUMAN_LAG1_STAT DOUBLE PRECISION,
    NUM_MISSING_OBS      DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (ID) ON DELETE CASCADE,
    FOREIGN KEY (SIM_RUN_ID_FK, ELEMENT_ID_FK)
        REFERENCES MODEL_ELEMENT (SIM_RUN_ID_FK, ELEMENT_ID) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ARS_ME_FK_INDEX ON ACROSS_REP_STAT(SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- WITHIN_REP_COUNTER_STAT represents within replication final value for each replication of
-- each simulation for each counter

CREATE TABLE WITHIN_REP_COUNTER_STAT
(
    ID            INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK INTEGER NOT NULL,
    SIM_RUN_ID_FK INTEGER NOT NULL,
    REP_NUM       INTEGER NOT NULL CHECK (REP_NUM >= 1),
    STAT_NAME     VARCHAR(510),
    LAST_VALUE    DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (ID) ON DELETE CASCADE,
    UNIQUE (ELEMENT_ID_FK, SIM_RUN_ID_FK, REP_NUM),
    FOREIGN KEY (SIM_RUN_ID_FK, ELEMENT_ID_FK)
        REFERENCES MODEL_ELEMENT (SIM_RUN_ID_FK, ELEMENT_ID) ON DELETE CASCADE
);

CREATE INDEX WRCS_ME_FK_INDEX ON WITHIN_REP_COUNTER_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- BATCH_STAT represents summary statistics for each simulation response across
-- the batches within a replication. This is produced only if the batch statistics
-- option is used when running the simulation.

CREATE TABLE BATCH_STAT
(
    ID                       INTEGER NOT NULL PRIMARY KEY,
    ELEMENT_ID_FK            INTEGER NOT NULL,
    SIM_RUN_ID_FK            INTEGER NOT NULL,
    REP_NUM                  INTEGER NOT NULL CHECK (REP_NUM >= 1),
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
    VON_NEUMAN_LAG1_STAT     DOUBLE PRECISION,
    NUM_MISSING_OBS          DOUBLE PRECISION,
    MIN_BATCH_SIZE           DOUBLE PRECISION,
    MIN_NUM_BATCHES          DOUBLE PRECISION,
    MAX_NUM_BATCHES_MULTIPLE DOUBLE PRECISION,
    MAX_NUM_BATCHES          DOUBLE PRECISION,
    NUM_REBATCHES            DOUBLE PRECISION,
    CURRENT_BATCH_SIZE       DOUBLE PRECISION,
    AMT_UNBATCHED            DOUBLE PRECISION,
    TOTAL_NUM_OBS            DOUBLE PRECISION,
    FOREIGN KEY (SIM_RUN_ID_FK) REFERENCES SIMULATION_RUN (ID) ON DELETE CASCADE,
    FOREIGN KEY (SIM_RUN_ID_FK, ELEMENT_ID_FK)
        REFERENCES MODEL_ELEMENT (SIM_RUN_ID_FK, ELEMENT_ID) ON DELETE CASCADE
);

CREATE INDEX BS_ME_FK_INDEX ON BATCH_STAT (SIM_RUN_ID_FK, ELEMENT_ID_FK);

-- WITHIN_REP_RESPONSE_VIEW represents a reduced view of within replication statistics containing only the average for the replication

CREATE VIEW WITHIN_REP_RESPONSE_VIEW (SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, AVERAGE)
AS
SELECT WITHIN_REP_STAT.SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, AVERAGE
FROM SIMULATION_RUN,
     MODEL_ELEMENT,
     WITHIN_REP_STAT
WHERE SIMULATION_RUN.ID = WITHIN_REP_STAT.SIM_RUN_ID_FK
  AND SIMULATION_RUN.ID = MODEL_ELEMENT.SIM_RUN_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = WITHIN_REP_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = WITHIN_REP_STAT.STAT_NAME
ORDER BY WITHIN_REP_STAT.SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM;

-- WITHIN_REP_COUNTER_VIEW represents a reduced view of within replication counters containing only the last value for the replication   

CREATE VIEW WITHIN_REP_COUNTER_VIEW (SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, LAST_VALUE)
AS
SELECT WITHIN_REP_COUNTER_STAT.SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, LAST_VALUE
FROM SIMULATION_RUN,
     WITHIN_REP_COUNTER_STAT,
     MODEL_ELEMENT
WHERE SIMULATION_RUN.ID = WITHIN_REP_COUNTER_STAT.SIM_RUN_ID_FK
  AND SIMULATION_RUN.ID = MODEL_ELEMENT.SIM_RUN_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = WITHIN_REP_COUNTER_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = WITHIN_REP_COUNTER_STAT.STAT_NAME
ORDER BY WITHIN_REP_COUNTER_STAT.SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM;

-- ACROSS_REP_VIEW represents a reduced view of the across replication responses containing only n, avg, and stddev       

CREATE VIEW ACROSS_REP_VIEW (SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV)
AS
SELECT ACROSS_REP_STAT.SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV
FROM SIMULATION_RUN,
     ACROSS_REP_STAT,
     MODEL_ELEMENT
WHERE SIMULATION_RUN.ID = ACROSS_REP_STAT.SIM_RUN_ID_FK
  AND SIMULATION_RUN.ID = MODEL_ELEMENT.SIM_RUN_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = ACROSS_REP_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = ACROSS_REP_STAT.STAT_NAME
ORDER BY STAT_NAME, ACROSS_REP_STAT.SIM_RUN_ID_FK, EXP_NAME;

-- BATCH_STAT_VIEW represents a reduced view of the batch statistics responses containing only n, avg, and stddev  

CREATE VIEW BATCH_STAT_VIEW (SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV)
AS
SELECT BATCH_STAT.SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, STAT_COUNT, AVERAGE, STD_DEV
FROM SIMULATION_RUN,
     BATCH_STAT,
     MODEL_ELEMENT
WHERE SIMULATION_RUN.ID = BATCH_STAT.SIM_RUN_ID_FK
  AND SIMULATION_RUN.ID = MODEL_ELEMENT.SIM_RUN_ID_FK
  AND MODEL_ELEMENT.ELEMENT_ID = BATCH_STAT.ELEMENT_ID_FK
  AND MODEL_ELEMENT.ELEMENT_NAME = BATCH_STAT.STAT_NAME
ORDER BY STAT_NAME, BATCH_STAT.SIM_RUN_ID_FK, EXP_NAME;

-- WITHIN_REP_VIEW combines the WITHIN_REP_COUNTER_VIEW and WITHIN_REP_RESPONSE_VIEW into one table from which across
-- replication or other statistical summaries by replication can be produced

CREATE VIEW WITHIN_REP_VIEW (SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, VALUE) AS
SELECT SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, AVERAGE AS VALUE
FROM WITHIN_REP_RESPONSE_VIEW
UNION
SELECT SIM_RUN_ID_FK, EXP_NAME, STAT_NAME, REP_NUM, LAST_VALUE AS VALUE
FROM WITHIN_REP_COUNTER_VIEW;

-- PW_DIFF_WITHIN_REP_VIEW computes the pairwise differences across difference simulation experiments
-- always takes the difference A - B, where A is a simulation run with a higher ID number than B

create view PW_DIFF_WITHIN_REP_VIEW
as
select SIMULATION_RUN.SIM_NAME,
       A.SIM_RUN_ID_FK                                 AS A_SIM_NUM,
       A.STAT_NAME,
       A.EXP_NAME                                      as A_EXP_NAME,
       A.REP_NUM,
       A.VALUE                                         as A_VALUE,
       B.SIM_RUN_ID_FK                                 as B_SIM_NUM,
       B.EXP_NAME                                      as B_EXP_NAME,
       B.VALUE                                         as B_VALUE,
       '(' || A.EXP_NAME || ' - ' || B.EXP_NAME || ')' as DIFF_NAME,
       (A.VALUE - B.VALUE)                             as A_MINUS_B
from WITHIN_REP_VIEW as A,
     WITHIN_REP_VIEW as B,
     SIMULATION_RUN
where A.SIM_RUN_ID_FK = SIMULATION_RUN.ID
  and A.STAT_NAME = B.STAT_NAME
  and A.REP_NUM = B.REP_NUM
  and A.SIM_RUN_ID_FK > B.SIM_RUN_ID_FK;
  
