ALTER TABLE transactions
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN updated_by_user_id UUID,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by_user_id UUID;

UPDATE transactions
SET updated_at = created_at;

ALTER TABLE transactions
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT uq_transactions_id_household_id UNIQUE (id, household_id),
    ADD CONSTRAINT fk_transactions_updater_household
        FOREIGN KEY (updated_by_user_id, household_id) REFERENCES users (id, household_id),
    ADD CONSTRAINT fk_transactions_deleter_household
        FOREIGN KEY (deleted_by_user_id, household_id) REFERENCES users (id, household_id),
    ADD CONSTRAINT ck_transactions_deleted_together CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL) OR
        (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL)
    ),
    DROP CONSTRAINT ck_transactions_source,
    ADD CONSTRAINT ck_transactions_source CHECK (
        source IN (
            'WHATSAPP_TEXT', 'WHATSAPP_AUDIO', 'WHATSAPP_IMAGE', 'DASHBOARD',
            'STATEMENT_IMPORT', 'CASH_FLOW_SETTLEMENT'
        )
    );

CREATE INDEX ix_transactions_household_active_occurred
    ON transactions (household_id, occurred_on DESC, created_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE cash_flow_entries
    ADD COLUMN updated_by_user_id UUID,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by_user_id UUID,
    ADD CONSTRAINT uq_cash_flow_entries_id_household_id UNIQUE (id, household_id),
    ADD CONSTRAINT fk_cash_flow_entries_updater_household
        FOREIGN KEY (updated_by_user_id, household_id) REFERENCES users (id, household_id),
    ADD CONSTRAINT fk_cash_flow_entries_deleter_household
        FOREIGN KEY (deleted_by_user_id, household_id) REFERENCES users (id, household_id),
    ADD CONSTRAINT ck_cash_flow_entries_deleted_together CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL) OR
        (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL)
    );

CREATE INDEX ix_cash_flow_entries_household_not_deleted_period
    ON cash_flow_entries (household_id, active, start_date, end_date)
    WHERE deleted_at IS NULL;

CREATE TABLE cash_flow_settlements (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    cash_flow_entry_id UUID NOT NULL,
    scheduled_on DATE NOT NULL,
    transaction_id UUID NOT NULL,
    settled_by_user_id UUID NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_cash_flow_settlements_entry_household
        FOREIGN KEY (cash_flow_entry_id, household_id)
            REFERENCES cash_flow_entries (id, household_id),
    CONSTRAINT fk_cash_flow_settlements_transaction_household
        FOREIGN KEY (transaction_id, household_id)
            REFERENCES transactions (id, household_id),
    CONSTRAINT fk_cash_flow_settlements_user_household
        FOREIGN KEY (settled_by_user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT uq_cash_flow_settlements_occurrence
        UNIQUE (household_id, cash_flow_entry_id, scheduled_on),
    CONSTRAINT uq_cash_flow_settlements_transaction UNIQUE (transaction_id)
);

CREATE INDEX ix_cash_flow_settlements_household_scheduled
    ON cash_flow_settlements (household_id, scheduled_on, settled_at);
