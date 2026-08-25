ALTER TABLE users
    ADD CONSTRAINT uq_users_id_household_id UNIQUE (id, household_id);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    created_by_user_id UUID NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    currency TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_accounts_author_household
        FOREIGN KEY (created_by_user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT uq_accounts_id_household_id UNIQUE (id, household_id),
    CONSTRAINT uq_accounts_id_household_currency UNIQUE (id, household_id, currency),
    CONSTRAINT ck_accounts_name CHECK (btrim(name) <> '' AND char_length(btrim(name)) <= 100),
    CONSTRAINT ck_accounts_type
        CHECK (type IN ('CHECKING', 'SAVINGS', 'CASH', 'CREDIT_CARD', 'INVESTMENT', 'OTHER')),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'BRL')
);

CREATE UNIQUE INDEX uq_accounts_household_lower_name
    ON accounts (household_id, lower(name));
CREATE INDEX ix_accounts_household_active_name
    ON accounts (household_id, active, name);

CREATE TABLE categories (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    created_by_user_id UUID NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_categories_author_household
        FOREIGN KEY (created_by_user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT uq_categories_id_household_id UNIQUE (id, household_id),
    CONSTRAINT uq_categories_id_household_type UNIQUE (id, household_id, type),
    CONSTRAINT ck_categories_name CHECK (btrim(name) <> '' AND char_length(btrim(name)) <= 100),
    CONSTRAINT ck_categories_type CHECK (type IN ('INCOME', 'EXPENSE'))
);

CREATE UNIQUE INDEX uq_categories_household_type_lower_name
    ON categories (household_id, type, lower(name));
CREATE INDEX ix_categories_household_active_type_name
    ON categories (household_id, active, type, name);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    created_by_user_id UUID NOT NULL,
    account_id UUID NOT NULL,
    type TEXT NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency TEXT NOT NULL,
    description TEXT NOT NULL,
    category_id UUID NOT NULL,
    occurred_on DATE NOT NULL,
    source TEXT NOT NULL,
    source_reference_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_transactions_author_household
        FOREIGN KEY (created_by_user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT fk_transactions_account_household_currency
        FOREIGN KEY (account_id, household_id, currency) REFERENCES accounts (id, household_id, currency),
    CONSTRAINT fk_transactions_category_household_type
        FOREIGN KEY (category_id, household_id, type) REFERENCES categories (id, household_id, type),
    CONSTRAINT ck_transactions_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_transactions_description
        CHECK (btrim(description) <> '' AND char_length(btrim(description)) <= 500),
    CONSTRAINT ck_transactions_source CHECK (
        source IN ('WHATSAPP_TEXT', 'WHATSAPP_AUDIO', 'WHATSAPP_IMAGE', 'DASHBOARD', 'STATEMENT_IMPORT')
    ),
    CONSTRAINT ck_transactions_source_reference CHECK (
        source_reference_id IS NULL OR
        (btrim(source_reference_id) <> '' AND char_length(btrim(source_reference_id)) <= 200)
    )
);

CREATE UNIQUE INDEX uq_transactions_household_source_reference
    ON transactions (household_id, source, source_reference_id)
    WHERE source_reference_id IS NOT NULL;
CREATE INDEX ix_transactions_household_occurred_created
    ON transactions (household_id, occurred_on DESC, created_at DESC);
CREATE INDEX ix_transactions_household_account_occurred
    ON transactions (household_id, account_id, occurred_on DESC);
CREATE INDEX ix_transactions_household_category_occurred
    ON transactions (household_id, category_id, occurred_on DESC);

CREATE TABLE cash_flow_entries (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    created_by_user_id UUID NOT NULL,
    type TEXT NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency TEXT NOT NULL,
    description TEXT NOT NULL,
    category_id UUID NOT NULL,
    recurrence TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    source TEXT NOT NULL,
    source_reference_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_cash_flow_entries_author_household
        FOREIGN KEY (created_by_user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT fk_cash_flow_entries_category_household_type
        FOREIGN KEY (category_id, household_id, type) REFERENCES categories (id, household_id, type),
    CONSTRAINT ck_cash_flow_entries_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_cash_flow_entries_amount CHECK (amount > 0),
    CONSTRAINT ck_cash_flow_entries_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_cash_flow_entries_description
        CHECK (btrim(description) <> '' AND char_length(btrim(description)) <= 500),
    CONSTRAINT ck_cash_flow_entries_recurrence CHECK (recurrence = 'ONCE'),
    CONSTRAINT ck_cash_flow_entries_end_date CHECK (end_date IS NULL),
    CONSTRAINT ck_cash_flow_entries_source CHECK (
        source IN ('WHATSAPP_TEXT', 'WHATSAPP_AUDIO', 'WHATSAPP_IMAGE', 'DASHBOARD', 'STATEMENT_IMPORT')
    ),
    CONSTRAINT ck_cash_flow_entries_source_reference CHECK (
        source_reference_id IS NULL OR
        (btrim(source_reference_id) <> '' AND char_length(btrim(source_reference_id)) <= 200)
    )
);

CREATE UNIQUE INDEX uq_cash_flow_entries_household_source_reference
    ON cash_flow_entries (household_id, source, source_reference_id)
    WHERE source_reference_id IS NOT NULL;
CREATE INDEX ix_cash_flow_entries_household_start_created
    ON cash_flow_entries (household_id, start_date, created_at);
