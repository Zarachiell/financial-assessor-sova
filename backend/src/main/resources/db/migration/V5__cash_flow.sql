ALTER TABLE cash_flow_entries
    DROP CONSTRAINT ck_cash_flow_entries_recurrence,
    DROP CONSTRAINT ck_cash_flow_entries_end_date,
    ALTER COLUMN category_id DROP NOT NULL,
    ADD COLUMN account_id UUID,
    ADD COLUMN day_of_month SMALLINT,
    ADD CONSTRAINT fk_cash_flow_entries_account_household_currency
        FOREIGN KEY (account_id, household_id, currency) REFERENCES accounts (id, household_id, currency),
    ADD CONSTRAINT ck_cash_flow_entries_recurrence
        CHECK (recurrence IN ('ONCE', 'MONTHLY')),
    ADD CONSTRAINT ck_cash_flow_entries_schedule CHECK (
        (recurrence = 'ONCE' AND day_of_month IS NULL) OR
        (recurrence = 'MONTHLY' AND day_of_month IS NOT NULL AND day_of_month BETWEEN 1 AND 31)
    ),
    ADD CONSTRAINT ck_cash_flow_entries_end_date
        CHECK (end_date IS NULL OR end_date >= start_date);

CREATE INDEX ix_cash_flow_entries_household_active_period
    ON cash_flow_entries (household_id, active, start_date, end_date);

CREATE INDEX ix_cash_flow_entries_household_account_period
    ON cash_flow_entries (household_id, account_id, start_date, end_date)
    WHERE account_id IS NOT NULL;
