CREATE TABLE households (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households (id),
    name TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE whatsapp_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    phone_number TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_whatsapp_identities_phone_number UNIQUE (phone_number),
    CONSTRAINT ck_whatsapp_identities_phone_number CHECK (phone_number ~ '^whatsapp:\+[1-9][0-9]{6,14}$')
);

CREATE TABLE whatsapp_messages (
    id UUID PRIMARY KEY,
    message_sid TEXT NOT NULL,
    direction TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES users (id),
    from_address TEXT NOT NULL,
    to_address TEXT NOT NULL,
    body TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_whatsapp_messages_message_sid UNIQUE (message_sid),
    CONSTRAINT ck_whatsapp_messages_direction CHECK (direction IN ('INBOUND', 'OUTBOUND'))
);

CREATE INDEX ix_users_household_id ON users (household_id);
CREATE INDEX ix_whatsapp_identities_user_id ON whatsapp_identities (user_id);
CREATE INDEX ix_whatsapp_messages_user_id_received_at ON whatsapp_messages (user_id, received_at DESC);
