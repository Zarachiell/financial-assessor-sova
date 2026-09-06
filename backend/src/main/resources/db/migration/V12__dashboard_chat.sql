CREATE TABLE dashboard_chat_messages (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    user_id UUID NOT NULL,
    exchange_id UUID NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dashboard_chat_user_household
        FOREIGN KEY (user_id, household_id) REFERENCES users (id, household_id),
    CONSTRAINT uq_dashboard_chat_exchange_role UNIQUE (exchange_id, role),
    CONSTRAINT ck_dashboard_chat_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_dashboard_chat_content CHECK (char_length(content) BETWEEN 1 AND 4000)
);

CREATE INDEX ix_dashboard_chat_user_created_at
    ON dashboard_chat_messages (user_id, created_at DESC);
