ALTER TABLE attachments
    ADD COLUMN household_id UUID,
    ADD COLUMN storage_key TEXT,
    ADD COLUMN sha256_hex TEXT;

UPDATE attachments attachment
SET household_id = app_user.household_id
FROM whatsapp_messages message
JOIN users app_user ON app_user.id = message.user_id
WHERE message.id = attachment.whatsapp_message_id;

ALTER TABLE attachments
    ALTER COLUMN household_id SET NOT NULL,
    ADD CONSTRAINT fk_attachments_household
        FOREIGN KEY (household_id) REFERENCES households (id),
    ADD CONSTRAINT uq_attachments_id_household UNIQUE (id, household_id),
    ADD CONSTRAINT ck_attachments_storage_key CHECK (
        storage_key IS NULL OR (
            btrim(storage_key) <> '' AND
            char_length(storage_key) <= 500 AND
            storage_key !~ '(^|/)\.\.(/|$)' AND
            storage_key !~ '^/'
        )
    ),
    ADD CONSTRAINT ck_attachments_sha256_hex CHECK (
        sha256_hex IS NULL OR sha256_hex ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_attachments_stored_media_complete CHECK (
        (storage_key IS NULL AND sha256_hex IS NULL) OR
        (storage_key IS NOT NULL AND sha256_hex IS NOT NULL AND size_bytes IS NOT NULL)
    );

CREATE INDEX ix_attachments_household_created
    ON attachments (household_id, created_at DESC);

ALTER TABLE processing_jobs
    DROP CONSTRAINT ck_processing_jobs_type,
    ADD CONSTRAINT ck_processing_jobs_type CHECK (
        type IN ('PROCESS_AUDIO_MESSAGE', 'PROCESS_IMAGE_MESSAGE')
    );

CREATE TABLE image_analyses (
    attachment_id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    document_type TEXT NOT NULL,
    merchant TEXT,
    amount NUMERIC(19, 2),
    occurred_on DATE,
    suggested_category TEXT,
    confidence NUMERIC(5, 4) NOT NULL,
    merchant_confidence NUMERIC(5, 4),
    amount_confidence NUMERIC(5, 4),
    date_confidence NUMERIC(5, 4),
    category_confidence NUMERIC(5, 4),
    confirmation_required BOOLEAN NOT NULL,
    status TEXT NOT NULL DEFAULT 'CANDIDATE',
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    analyzed_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_image_analyses_attachment_household
        FOREIGN KEY (attachment_id, household_id) REFERENCES attachments (id, household_id),
    CONSTRAINT ck_image_analyses_document_type CHECK (
        document_type IN ('RECEIPT', 'INVOICE', 'PAYMENT_PROOF', 'OTHER')
    ),
    CONSTRAINT ck_image_analyses_merchant CHECK (
        merchant IS NULL OR (btrim(merchant) <> '' AND char_length(merchant) <= 200)
    ),
    CONSTRAINT ck_image_analyses_amount CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT ck_image_analyses_category CHECK (
        suggested_category IS NULL OR (
            btrim(suggested_category) <> '' AND char_length(suggested_category) <= 100
        )
    ),
    CONSTRAINT ck_image_analyses_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_image_analyses_merchant_confidence CHECK (
        merchant_confidence IS NULL OR merchant_confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_image_analyses_amount_confidence CHECK (
        amount_confidence IS NULL OR amount_confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_image_analyses_date_confidence CHECK (
        date_confidence IS NULL OR date_confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_image_analyses_category_confidence CHECK (
        category_confidence IS NULL OR category_confidence BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_image_analyses_status CHECK (status IN ('CANDIDATE', 'CONSUMED', 'DISMISSED')),
    CONSTRAINT ck_image_analyses_consumed_at CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL) OR
        (status <> 'CONSUMED' AND consumed_at IS NULL)
    )
);

CREATE INDEX ix_image_analyses_household_status_analyzed
    ON image_analyses (household_id, status, analyzed_at DESC);
