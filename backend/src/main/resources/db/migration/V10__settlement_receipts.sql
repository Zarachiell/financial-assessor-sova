ALTER TABLE cash_flow_settlements
    ADD CONSTRAINT uq_cash_flow_settlements_id_household UNIQUE (id, household_id);

CREATE TABLE settlement_receipts (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    settlement_id UUID NOT NULL,
    attachment_id UUID NOT NULL,
    caption TEXT,
    linked_by_user_id UUID NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_settlement_receipts_settlement_household
        FOREIGN KEY (settlement_id, household_id)
            REFERENCES cash_flow_settlements (id, household_id),
    CONSTRAINT fk_settlement_receipts_attachment_household
        FOREIGN KEY (attachment_id, household_id) REFERENCES attachments (id, household_id),
    CONSTRAINT fk_settlement_receipts_user_household
        FOREIGN KEY (linked_by_user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT uq_settlement_receipts_settlement_attachment
        UNIQUE (settlement_id, attachment_id),
    CONSTRAINT ck_settlement_receipts_caption CHECK (
        caption IS NULL OR (btrim(caption) <> '' AND char_length(caption) <= 500)
    )
);

CREATE INDEX ix_settlement_receipts_household_settlement
    ON settlement_receipts (household_id, settlement_id, linked_at DESC);

CREATE INDEX ix_settlement_receipts_household_attachment
    ON settlement_receipts (household_id, attachment_id);
