-- Custom user tables used by the toolkit (ANSI SQL; adjust types for SQL Server / Oracle).

-- Trade events written by the post-process step and consumed by outbound interfaces.
CREATE TABLE USER_TRADE_EVENTS (
    event_id            BIGINT        NOT NULL PRIMARY KEY,
    deal_tracking_num   BIGINT        NOT NULL,
    tran_num            BIGINT        NOT NULL,
    version             INT           NOT NULL,
    action              VARCHAR(16)   NOT NULL,  -- BOOK, VALIDATE, AMEND, CANCEL, MATURE
    from_status         VARCHAR(16)   NOT NULL,
    to_status           VARCHAR(16)   NOT NULL,
    event_time          TIMESTAMP     NOT NULL,
    processed           SMALLINT      DEFAULT 0 NOT NULL
);
CREATE INDEX ix_user_trade_events_pending ON USER_TRADE_EVENTS (processed, event_time);
CREATE UNIQUE INDEX ux_user_trade_events_tran ON USER_TRADE_EVENTS (tran_num, action);

-- Audit of ops-service rule outcomes for every attempted status change.
CREATE TABLE USER_OPS_RULE_AUDIT (
    audit_id            BIGINT        NOT NULL PRIMARY KEY,
    deal_tracking_num   BIGINT        NOT NULL,
    tran_num            BIGINT,
    target_status       VARCHAR(16)   NOT NULL,
    rule_name           VARCHAR(64)   NOT NULL,
    severity            VARCHAR(8)    NOT NULL,  -- PASS, WARN, BLOCK
    message             VARCHAR(1000),
    created_at          TIMESTAMP     NOT NULL
);
CREATE INDEX ix_user_ops_rule_audit_deal ON USER_OPS_RULE_AUDIT (deal_tracking_num, created_at);

-- Counterparties with payment netting agreements.
CREATE TABLE USER_NETTING_AGREEMENT (
    external_bu         VARCHAR(64)   NOT NULL,
    currency            CHAR(3)       NOT NULL,
    effective_date      DATE          NOT NULL,
    end_date            DATE,
    PRIMARY KEY (external_bu, currency, effective_date)
);

-- Clearing broker position statement for reconciliation.
CREATE TABLE USER_BROKER_POSITION (
    statement_date      DATE          NOT NULL,
    broker              VARCHAR(64)   NOT NULL,
    index_name          VARCHAR(64)   NOT NULL,
    contract_month      CHAR(7)       NOT NULL,  -- YYYY-MM
    net_quantity        DECIMAL(20,4) NOT NULL,
    PRIMARY KEY (statement_date, broker, index_name, contract_month)
);

-- Reconciliation breaks raised for operations.
CREATE TABLE USER_RECON_BREAK (
    break_id            BIGINT        NOT NULL PRIMARY KEY,
    statement_date      DATE          NOT NULL,
    index_name          VARCHAR(64)   NOT NULL,
    contract_month      CHAR(7)       NOT NULL,
    internal_quantity   DECIMAL(20,4) NOT NULL,
    broker_quantity     DECIMAL(20,4) NOT NULL,
    status              VARCHAR(16)   DEFAULT 'OPEN' NOT NULL,
    resolution_note     VARCHAR(1000)
);
